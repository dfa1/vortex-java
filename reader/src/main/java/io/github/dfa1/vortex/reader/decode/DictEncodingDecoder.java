package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.DictMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/// Read-only decoder for `vortex.dict`.
///
/// The primitive-value paths (`decodeRustProto`, `decodeLegacyJava`)
/// eagerly expand `codes` and `values` into a contiguous output segment
/// via `expandU8/U16/U32` — these mirror the broadcast-aware scatter loop
/// with `SegmentBroadcast.capacity` (ConstantEncoding fan-out), so the
/// output is materialised at decode time. ADR 0012's lazy-dict scope is the
/// layout-level path in `ScanIterator.decodeDictLayout`, which is now lazy
/// via `DictXxxArray`; this encoding-level path runs only when a parent
/// decoder explicitly calls `decodeChild` on a `vortex.dict` segment,
/// which is rarer and downstream-flat-segment-dependent. The broadcast semantics
/// make lazy wrapping non-trivial here — kept eager by design.
public final class DictEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public DictEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DICT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive || dtype instanceof DType.Utf8;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer meta = ctx.metadata();

        if (ctx.dtype() instanceof DType.Utf8) {
            if (ctx.node().children().length == 0) {
                if (meta == null || !meta.hasRemaining()) {
                    throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata for legacy utf8 dict");
                }
                return decodeUtf8DictLegacy(ctx, meta);
            }
            if (meta == null || !meta.hasRemaining()) {
                throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata for utf8 dict");
            }
            return decodeUtf8DictProto(ctx, meta.duplicate());
        }

        if (meta == null || !meta.hasRemaining()) {
            throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata");
        }

        if (meta.remaining() == 1) {
            return decodeLegacyJava(ctx, meta.get(0));
        }
        return decodeRustProto(ctx, meta.duplicate());
    }

    private static Array decodeLegacyJava(DecodeContext ctx, byte codeTypeByte) {
        PType codePType = PType.fromOrdinal(Byte.toUnsignedInt(codeTypeByte));
        PType valPType = ((DType.Primitive) ctx.dtype()).ptype();
        int elemSize = valPType.byteSize();
        long rowCount = ctx.rowCount();

        MemorySegment valuesBuf = ctx.segmentBuffers()[ctx.node().children()[0].bufferIndices()[0]];

        DType codesDtype = new DType.Primitive(codePType, false);
        MemorySegment codesBuf = ctx.decodeChildSegment(1, codesDtype, rowCount);

        MemorySegment out = ctx.arena().allocate(rowCount * (long) elemSize);
        switch (codePType) {
            case U8 -> expandU8(codesBuf, valuesBuf, out, rowCount, elemSize);
            case U16 -> expandU16(codesBuf, valuesBuf, out, rowCount, elemSize);
            case U32 -> expandU32(codesBuf, valuesBuf, out, rowCount, elemSize);
            default -> {
                for (long i = 0; i < rowCount; i++) {
                    long code = readCode(codesBuf, codePType, i);
                    MemorySegment.copy(valuesBuf, code * elemSize, out, i * elemSize, elemSize);
                }
            }
        }
        return typedArray(ctx.dtype(), valPType, rowCount, out.asReadOnly());
    }

    private static Array decodeRustProto(DecodeContext ctx, ByteBuffer metaBuf) {
        DictMetadata meta;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(metaBuf);
            meta = DictMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_DICT, "invalid proto metadata", e);
        }

        PType codePType = PType.fromOrdinal(meta.codes_ptype().value());
        long valuesLen = meta.values_len();
        long rowCount = ctx.rowCount();
        PType valPType = ((DType.Primitive) ctx.dtype()).ptype();
        int elemSize = valPType.byteSize();

        DType codesDtype = new DType.Primitive(codePType, false);
        MemorySegment codesBuf = ctx.decodeChildSegment(0, codesDtype, rowCount);
        MemorySegment valuesBuf = ctx.decodeChildSegment(1, ctx.dtype(), valuesLen);

        MemorySegment out = ctx.arena().allocate(rowCount * (long) elemSize);
        switch (codePType) {
            case U8 -> expandU8(codesBuf, valuesBuf, out, rowCount, elemSize);
            case U16 -> expandU16(codesBuf, valuesBuf, out, rowCount, elemSize);
            case U32 -> expandU32(codesBuf, valuesBuf, out, rowCount, elemSize);
            default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
        }
        return typedArray(ctx.dtype(), valPType, rowCount, out.asReadOnly());
    }

    private static Array decodeUtf8DictLegacy(DecodeContext ctx, ByteBuffer meta) {
        PType codePType = PType.fromOrdinal(Byte.toUnsignedInt(meta.get(0)));
        long n = ctx.rowCount();

        MemorySegment dictBytes = ctx.buffer(0);
        MemorySegment dictOffsets = ctx.buffer(1);
        MemorySegment codes = ctx.buffer(2);

        return VarBinArray.ofDict(ctx.dtype(), n,
                dictBytes, dictOffsets, PType.I64,
                codes, codePType);
    }

    private static Array decodeUtf8DictProto(DecodeContext ctx, ByteBuffer metaBuf) {
        DictMetadata meta;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(metaBuf);
            meta = DictMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_DICT, "invalid utf8 dict proto metadata", e);
        }
        PType codePType = PType.fromOrdinal(meta.codes_ptype().value());
        long dictSize = meta.values_len();
        long n = ctx.rowCount();

        DType codesDtype = new DType.Primitive(codePType, false);
        MemorySegment codesBuf = ctx.decodeChildSegment(0, codesDtype, n);

        VarBinArray valuesArr = (VarBinArray) ctx.decodeChild(1, ctx.dtype(), dictSize);
        VarBinArray.OffsetMode dictValues = VarBinArray.toOffsetMode(valuesArr, ctx.arena());

        return VarBinArray.ofDict(ctx.dtype(), n,
                dictValues.bytesSegment(), dictValues.offsetsSegment(), PType.I64,
                codesBuf, codePType);
    }

    private static long readCode(MemorySegment buf, PType codePType, long i) {
        long cap = SegmentBroadcast.capacity(buf, codePType.byteSize());
        long idx = i % cap;
        return switch (codePType) {
            case U8 -> Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, idx));
            case U16 -> Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, idx * 2));
            case U32 -> Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, idx * 4));
            default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
        };
    }

    private static void expandU8(MemorySegment codes, MemorySegment values, MemorySegment out, long rowCount, int elemSize) {
        long codesCap = SegmentBroadcast.capacity(codes, 1);
        long valuesCap = SegmentBroadcast.capacity(values, elemSize);
        boolean fast = codesCap >= rowCount && valuesCap > 1;
        switch (elemSize) {
            case 8 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        out.setAtIndex(PTypeIO.LE_LONG, i, values.getAtIndex(PTypeIO.LE_LONG, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i % codesCap));
                        out.setAtIndex(PTypeIO.LE_LONG, i, values.getAtIndex(PTypeIO.LE_LONG, code % valuesCap));
                    }
                }
            }
            case 4 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        out.setAtIndex(PTypeIO.LE_INT, i, values.getAtIndex(PTypeIO.LE_INT, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i % codesCap));
                        out.setAtIndex(PTypeIO.LE_INT, i, values.getAtIndex(PTypeIO.LE_INT, code % valuesCap));
                    }
                }
            }
            case 2 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        out.setAtIndex(PTypeIO.LE_SHORT, i, values.getAtIndex(PTypeIO.LE_SHORT, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i % codesCap));
                        out.setAtIndex(PTypeIO.LE_SHORT, i, values.getAtIndex(PTypeIO.LE_SHORT, code % valuesCap));
                    }
                }
            }
            case 1 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i % codesCap));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code % valuesCap));
                    }
                }
            }
            default -> {
                if (fast) {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        MemorySegment.copy(values, code * elemSize, out, outOff, elemSize);
                    }
                } else {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i % codesCap));
                        MemorySegment.copy(values, (code % valuesCap) * elemSize, out, outOff, elemSize);
                    }
                }
            }
        }
    }

    private static void expandU16(MemorySegment codes, MemorySegment values, MemorySegment out, long rowCount, int elemSize) {
        long codesCap = SegmentBroadcast.capacity(codes, 2);
        long valuesCap = SegmentBroadcast.capacity(values, elemSize);
        boolean fast = codesCap >= rowCount && valuesCap > 1;
        switch (elemSize) {
            case 8 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, i * 2));
                        out.setAtIndex(PTypeIO.LE_LONG, i, values.getAtIndex(PTypeIO.LE_LONG, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, (i % codesCap) * 2));
                        out.setAtIndex(PTypeIO.LE_LONG, i, values.getAtIndex(PTypeIO.LE_LONG, code % valuesCap));
                    }
                }
            }
            case 4 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, i * 2));
                        out.setAtIndex(PTypeIO.LE_INT, i, values.getAtIndex(PTypeIO.LE_INT, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, (i % codesCap) * 2));
                        out.setAtIndex(PTypeIO.LE_INT, i, values.getAtIndex(PTypeIO.LE_INT, code % valuesCap));
                    }
                }
            }
            case 2 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, i * 2));
                        out.setAtIndex(PTypeIO.LE_SHORT, i, values.getAtIndex(PTypeIO.LE_SHORT, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, (i % codesCap) * 2));
                        out.setAtIndex(PTypeIO.LE_SHORT, i, values.getAtIndex(PTypeIO.LE_SHORT, code % valuesCap));
                    }
                }
            }
            case 1 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, i * 2));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, (i % codesCap) * 2));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code % valuesCap));
                    }
                }
            }
            default -> {
                if (fast) {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, i * 2));
                        MemorySegment.copy(values, code * elemSize, out, outOff, elemSize);
                    }
                } else {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, (i % codesCap) * 2));
                        MemorySegment.copy(values, (code % valuesCap) * elemSize, out, outOff, elemSize);
                    }
                }
            }
        }
    }

    private static void expandU32(MemorySegment codes, MemorySegment values, MemorySegment out, long rowCount, int elemSize) {
        long codesCap = SegmentBroadcast.capacity(codes, 4);
        long valuesCap = SegmentBroadcast.capacity(values, elemSize);
        boolean fast = codesCap >= rowCount && valuesCap > 1;
        switch (elemSize) {
            case 8 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, i * 4));
                        out.setAtIndex(PTypeIO.LE_LONG, i, values.getAtIndex(PTypeIO.LE_LONG, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, (i % codesCap) * 4));
                        out.setAtIndex(PTypeIO.LE_LONG, i, values.getAtIndex(PTypeIO.LE_LONG, code % valuesCap));
                    }
                }
            }
            case 4 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, i * 4));
                        out.setAtIndex(PTypeIO.LE_INT, i, values.getAtIndex(PTypeIO.LE_INT, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, (i % codesCap) * 4));
                        out.setAtIndex(PTypeIO.LE_INT, i, values.getAtIndex(PTypeIO.LE_INT, code % valuesCap));
                    }
                }
            }
            case 2 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, i * 4));
                        out.setAtIndex(PTypeIO.LE_SHORT, i, values.getAtIndex(PTypeIO.LE_SHORT, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, (i % codesCap) * 4));
                        out.setAtIndex(PTypeIO.LE_SHORT, i, values.getAtIndex(PTypeIO.LE_SHORT, code % valuesCap));
                    }
                }
            }
            case 1 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, i * 4));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, (i % codesCap) * 4));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code % valuesCap));
                    }
                }
            }
            default -> {
                if (fast) {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, i * 4));
                        MemorySegment.copy(values, code * elemSize, out, outOff, elemSize);
                    }
                } else {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, (i % codesCap) * 4));
                        MemorySegment.copy(values, (code % valuesCap) * elemSize, out, outOff, elemSize);
                    }
                }
            }
        }
    }

    private static Array typedArray(DType dtype, PType ptype, long n, MemorySegment seg) {
        return switch (ptype) {
            case I64, U64 -> new MaterializedLongArray(dtype, n, seg);
            case I32, U32 -> new MaterializedIntArray(dtype, n, seg);
            case F64 -> new MaterializedDoubleArray(dtype, n, seg);
            case F32 -> new MaterializedFloatArray(dtype, n, seg);
            case I16, U16 -> new MaterializedShortArray(dtype, n, seg);
            case I8, U8 -> new MaterializedByteArray(dtype, n, seg);
            default -> throw new VortexException(EncodingId.VORTEX_DICT, "unsupported ptype " + ptype);
        };
    }
}
