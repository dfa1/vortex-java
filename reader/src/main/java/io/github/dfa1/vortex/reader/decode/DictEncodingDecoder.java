package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoDictMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
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

/// Read-only decoder for `vortex.dict`.
///
/// The primitive-value paths (`decodeRustProto`, `decodeLegacyJava`)
/// eagerly expand `codes` and `values` into a contiguous output segment
/// via `expandU8/U16/U32` — these mirror the broadcast-aware scatter loop
/// with `SegmentBroadcast.capacity` (ConstantEncoding fan-out), so the
/// output is materialized at decode time. ADR 0012's lazy-dict scope is the
/// layout-level path in `ScanIterator.decodeDictLayout`, which is now lazy
/// via `DictXxxArray`; this encoding-level path runs only when a parent
/// decoder explicitly calls `decodeChild` on a `vortex.dict` segment,
/// which is rarer and downstream-flat-segment-dependent. The broadcast semantics
/// make lazy wrapping non-trivial here — kept eager by design.
public final class DictEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DICT;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment meta = ctx.metadata();

        if (ctx.dtype() instanceof DType.Utf8) {
            if (ctx.node().children().length == 0) {
                if (meta == null || meta.byteSize() == 0) {
                    throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata for legacy utf8 dict");
                }
                return decodeUtf8DictLegacy(ctx, meta);
            }
            if (meta == null || meta.byteSize() == 0) {
                throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata for utf8 dict");
            }
            return decodeUtf8DictProto(ctx, meta);
        }

        if (meta == null || meta.byteSize() == 0) {
            throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata");
        }

        if (meta.byteSize() == 1) {
            return decodeLegacyJava(ctx, meta.get(ValueLayout.JAVA_BYTE, 0));
        }
        return decodeRustProto(ctx, meta);
    }

    private static Array decodeLegacyJava(DecodeContext ctx, byte codeTypeByte) {
        PType codePType = PType.fromOrdinal(Byte.toUnsignedInt(codeTypeByte));
        PType valPType = ((DType.Primitive) ctx.dtype()).ptype();
        int elemSize = valPType.byteSize();
        long rowCount = ctx.rowCount();

        MemorySegment valuesBuf = ctx.segmentBuffers()[ctx.node().children()[0].bufferIndices()[0]];

        DType codesDtype = new DType.Primitive(codePType, false);
        MemorySegment codesBuf = ctx.decodeChildSegment(1, codesDtype, rowCount);

        MemorySegment out = ctx.arena().allocate(rowCount * elemSize);
        switch (codePType) {
            case U8 -> expandU8(codesBuf, valuesBuf, out, rowCount, elemSize);
            case U16 -> expandU16(codesBuf, valuesBuf, out, rowCount, elemSize);
            case U32 -> expandU32(codesBuf, valuesBuf, out, rowCount, elemSize);
            default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
        }
        return typedArray(ctx.dtype(), valPType, rowCount, out.asReadOnly());
    }

    private static Array decodeRustProto(DecodeContext ctx, MemorySegment metaBuf) {
        ProtoDictMetadata meta;
        try {
            MemorySegment metaSeg = metaBuf;
            meta = ProtoDictMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_DICT, "invalid proto metadata", e);
        }

        PType codePType = PType.fromOrdinal(meta.codes_ptype().value());
        long valuesLen = meta.values_len();
        long rowCount = ctx.rowCount();
        PType valPType = ((DType.Primitive) ctx.dtype()).ptype();
        int elemSize = valPType.byteSize();

        // Row validity mirrors the Rust reference: a DictArray row is null when its CODE
        // is null (codes-side validity child) or when the code points at an invalid pool
        // slot (pool-null representation). Both arrive as MaskedArray children and must
        // be propagated per row, not flattened away (#210).
        DType codesDtype = new DType.Primitive(codePType, ctx.dtype().nullable());
        Array codesArr = ctx.decodeChild(0, codesDtype, rowCount);
        BoolArray codesValidity = null;
        Array rawCodes = codesArr;
        if (codesArr instanceof MaskedArray masked) {
            rawCodes = masked.inner();
            codesValidity = masked.validity();
        }
        MemorySegment codesBuf = ctx.materialize(rawCodes);

        Array valuesArr = ctx.decodeChild(1, ctx.dtype(), valuesLen);
        BoolArray poolValidity = null;
        Array rawValues = valuesArr;
        if (valuesArr instanceof MaskedArray masked) {
            rawValues = masked.inner();
            poolValidity = masked.validity();
        }
        MemorySegment valuesBuf = ctx.materialize(rawValues);

        MemorySegment out = ctx.arena().allocate(rowCount * elemSize);
        switch (codePType) {
            case U8 -> expandU8(codesBuf, valuesBuf, out, rowCount, elemSize);
            case U16 -> expandU16(codesBuf, valuesBuf, out, rowCount, elemSize);
            case U32 -> expandU32(codesBuf, valuesBuf, out, rowCount, elemSize);
            default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
        }
        Array values = typedArray(ctx.dtype(), valPType, rowCount, out.asReadOnly());
        BoolArray rowValidity = rowValidity(ctx, codesBuf, codePType, codesValidity, poolValidity, rowCount);
        return rowValidity == null ? values : new MaskedArray(values, rowValidity);
    }

    /// Combines codes-side and pool-side validity into per-row validity: row `i` is
    /// valid iff its code is valid and the pool slot the code references is valid.
    /// Returns `null` when neither side carries validity (all rows valid), and the
    /// codes-side mask unchanged when the pool is all-valid (it is already per-row).
    /// Output is bit-packed LSB-first ([MaterializedBoolArray] layout). The broadcast
    /// branch mirrors the `expandXxx` loops (undersized codes buffer = ConstantEncoding
    /// fan-out) and is split out of the fast path.
    ///
    /// @param ctx           decode context (allocation arena)
    /// @param codesBuf      decoded raw codes buffer
    /// @param codePType     unsigned code ptype (U8/U16/U32)
    /// @param codesValidity per-row validity from the codes child, or `null`
    /// @param poolValidity  validity of the values pool, or `null`
    /// @param rowCount      logical row count
    /// @return a bit-packed row validity array of `rowCount` bits, or `null` when all valid
    private static BoolArray rowValidity(DecodeContext ctx, MemorySegment codesBuf, PType codePType,
            BoolArray codesValidity, BoolArray poolValidity, long rowCount) {
        if (poolValidity == null) {
            return codesValidity;
        }
        MemorySegment bits = ctx.arena().allocate((rowCount + 7) >>> 3);
        long codesCap = SegmentBroadcast.capacity(codesBuf, codePType.byteSize());
        if (codesCap >= rowCount) {
            for (long i = 0; i < rowCount; i++) {
                boolean valid = (codesValidity == null || codesValidity.getBoolean(i))
                        && poolValid(poolValidity, readCode(codesBuf, i, codePType));
                if (valid) {
                    setBit(bits, i);
                }
            }
        } else {
            for (long i = 0; i < rowCount; i++) {
                boolean valid = (codesValidity == null || codesValidity.getBoolean(i))
                        && poolValid(poolValidity, readCode(codesBuf, i % codesCap, codePType));
                if (valid) {
                    setBit(bits, i);
                }
            }
        }
        return new MaterializedBoolArray(DType.BOOL, rowCount, bits.asReadOnly());
    }

    /// Untrusted-input guard: a malformed file may carry codes outside the pool, and the
    /// validity bitmap lookup must fail as [VortexException], never as a raw JDK
    /// IndexOutOfBoundsException.
    private static boolean poolValid(BoolArray poolValidity, long code) {
        if (code >= poolValidity.length()) {
            throw new VortexException(EncodingId.VORTEX_DICT,
                    "code " + code + " out of range for pool validity of length " + poolValidity.length());
        }
        return poolValidity.getBoolean(code);
    }

    private static long readCode(MemorySegment codes, long i, PType codePType) {
        return switch (codePType) {
            case U8 -> Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
            case U16 -> Short.toUnsignedLong(codes.getAtIndex(VortexFormat.LE_SHORT, i));
            case U32 -> Integer.toUnsignedLong(codes.getAtIndex(VortexFormat.LE_INT, i));
            default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
        };
    }

    private static void setBit(MemorySegment bits, long i) {
        long byteIdx = i >>> 3;
        byte cur = bits.get(ValueLayout.JAVA_BYTE, byteIdx);
        bits.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) ((cur & 0xff) | (1 << (i & 7))));
    }

    private static Array decodeUtf8DictLegacy(DecodeContext ctx, MemorySegment meta) {
        PType codePType = PType.fromOrdinal(Byte.toUnsignedInt(meta.get(ValueLayout.JAVA_BYTE, 0)));
        long n = ctx.rowCount();

        MemorySegment dictBytes = ctx.buffer(0);
        MemorySegment dictOffsets = ctx.buffer(1);
        MemorySegment codes = ctx.buffer(2);

        return VarBinArray.ofDict(ctx.dtype(), n,
                dictBytes, dictOffsets, PType.I64,
                codes, codePType);
    }

    private static Array decodeUtf8DictProto(DecodeContext ctx, MemorySegment metaBuf) {
        ProtoDictMetadata meta;
        try {
            MemorySegment metaSeg = metaBuf;
            meta = ProtoDictMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_DICT, "invalid utf8 dict proto metadata", e);
        }
        PType codePType = PType.fromOrdinal(meta.codes_ptype().value());
        long dictSize = meta.values_len();
        long n = ctx.rowCount();

        // Same two null representations as the primitive path (#210): codes-side
        // validity and/or an invalid pool slot referenced by null rows.
        DType codesDtype = new DType.Primitive(codePType, ctx.dtype().nullable());
        Array codesArr = ctx.decodeChild(0, codesDtype, n);
        BoolArray codesValidity = null;
        Array rawCodes = codesArr;
        if (codesArr instanceof MaskedArray masked) {
            rawCodes = masked.inner();
            codesValidity = masked.validity();
        }
        MemorySegment codesBuf = ctx.materialize(rawCodes);

        Array valuesDecoded = ctx.decodeChild(1, ctx.dtype(), dictSize);
        BoolArray poolValidity = null;
        if (valuesDecoded instanceof MaskedArray masked) {
            valuesDecoded = masked.inner();
            poolValidity = masked.validity();
        }
        VarBinArray valuesArr = (VarBinArray) valuesDecoded;
        VarBinArray.OffsetMode dictValues = VarBinArray.toOffsetMode(valuesArr, ctx.arena());

        BoolArray rowValidity = rowValidity(ctx, codesBuf, codePType, codesValidity, poolValidity, n);
        // Carry the offsets ptype that `dictValues` actually materialized. `toOffsetMode`
        // only builds fresh I64 offsets on its slow path; on the fast path it returns the
        // decoded values array unchanged, keeping its own ptype (e.g. FSST decompresses to
        // I32 offsets). Hardcoding I64 here made the DictMode carrier disagree with its
        // buffer width (4-byte stride read as 8) and threw IOOBE — see #215.
        Array dict = VarBinArray.ofDict(ctx.dtype(), n,
                dictValues.bytesSegment(), dictValues.offsetsSegment(), dictValues.offsetsPtype(),
                codesBuf, codePType);
        return rowValidity == null ? dict : new MaskedArray(dict, rowValidity);
    }

    static void expandU8(MemorySegment codes, MemorySegment values, MemorySegment out, long rowCount, int elemSize) {
        long codesCap = SegmentBroadcast.capacity(codes, 1);
        long valuesCap = SegmentBroadcast.capacity(values, elemSize);
        boolean fast = codesCap >= rowCount && valuesCap > 1;
        switch (elemSize) {
            case 8 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        out.setAtIndex(VortexFormat.LE_LONG, i, values.getAtIndex(VortexFormat.LE_LONG, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i % codesCap));
                        out.setAtIndex(VortexFormat.LE_LONG, i, values.getAtIndex(VortexFormat.LE_LONG, code % valuesCap));
                    }
                }
            }
            case 4 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        out.setAtIndex(VortexFormat.LE_INT, i, values.getAtIndex(VortexFormat.LE_INT, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i % codesCap));
                        out.setAtIndex(VortexFormat.LE_INT, i, values.getAtIndex(VortexFormat.LE_INT, code % valuesCap));
                    }
                }
            }
            case 2 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        out.setAtIndex(VortexFormat.LE_SHORT, i, values.getAtIndex(VortexFormat.LE_SHORT, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i % codesCap));
                        out.setAtIndex(VortexFormat.LE_SHORT, i, values.getAtIndex(VortexFormat.LE_SHORT, code % valuesCap));
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

    static void expandU16(MemorySegment codes, MemorySegment values, MemorySegment out, long rowCount, int elemSize) {
        long codesCap = SegmentBroadcast.capacity(codes, 2);
        long valuesCap = SegmentBroadcast.capacity(values, elemSize);
        boolean fast = codesCap >= rowCount && valuesCap > 1;
        switch (elemSize) {
            case 8 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(VortexFormat.LE_SHORT, i * 2));
                        out.setAtIndex(VortexFormat.LE_LONG, i, values.getAtIndex(VortexFormat.LE_LONG, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(VortexFormat.LE_SHORT, (i % codesCap) * 2));
                        out.setAtIndex(VortexFormat.LE_LONG, i, values.getAtIndex(VortexFormat.LE_LONG, code % valuesCap));
                    }
                }
            }
            case 4 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(VortexFormat.LE_SHORT, i * 2));
                        out.setAtIndex(VortexFormat.LE_INT, i, values.getAtIndex(VortexFormat.LE_INT, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(VortexFormat.LE_SHORT, (i % codesCap) * 2));
                        out.setAtIndex(VortexFormat.LE_INT, i, values.getAtIndex(VortexFormat.LE_INT, code % valuesCap));
                    }
                }
            }
            case 2 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(VortexFormat.LE_SHORT, i * 2));
                        out.setAtIndex(VortexFormat.LE_SHORT, i, values.getAtIndex(VortexFormat.LE_SHORT, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(VortexFormat.LE_SHORT, (i % codesCap) * 2));
                        out.setAtIndex(VortexFormat.LE_SHORT, i, values.getAtIndex(VortexFormat.LE_SHORT, code % valuesCap));
                    }
                }
            }
            case 1 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(VortexFormat.LE_SHORT, i * 2));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(VortexFormat.LE_SHORT, (i % codesCap) * 2));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code % valuesCap));
                    }
                }
            }
            default -> {
                if (fast) {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Short.toUnsignedLong(codes.get(VortexFormat.LE_SHORT, i * 2));
                        MemorySegment.copy(values, code * elemSize, out, outOff, elemSize);
                    }
                } else {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Short.toUnsignedLong(codes.get(VortexFormat.LE_SHORT, (i % codesCap) * 2));
                        MemorySegment.copy(values, (code % valuesCap) * elemSize, out, outOff, elemSize);
                    }
                }
            }
        }
    }

    static void expandU32(MemorySegment codes, MemorySegment values, MemorySegment out, long rowCount, int elemSize) {
        long codesCap = SegmentBroadcast.capacity(codes, 4);
        long valuesCap = SegmentBroadcast.capacity(values, elemSize);
        boolean fast = codesCap >= rowCount && valuesCap > 1;
        switch (elemSize) {
            case 8 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(VortexFormat.LE_INT, i * 4));
                        out.setAtIndex(VortexFormat.LE_LONG, i, values.getAtIndex(VortexFormat.LE_LONG, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(VortexFormat.LE_INT, (i % codesCap) * 4));
                        out.setAtIndex(VortexFormat.LE_LONG, i, values.getAtIndex(VortexFormat.LE_LONG, code % valuesCap));
                    }
                }
            }
            case 4 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(VortexFormat.LE_INT, i * 4));
                        out.setAtIndex(VortexFormat.LE_INT, i, values.getAtIndex(VortexFormat.LE_INT, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(VortexFormat.LE_INT, (i % codesCap) * 4));
                        out.setAtIndex(VortexFormat.LE_INT, i, values.getAtIndex(VortexFormat.LE_INT, code % valuesCap));
                    }
                }
            }
            case 2 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(VortexFormat.LE_INT, i * 4));
                        out.setAtIndex(VortexFormat.LE_SHORT, i, values.getAtIndex(VortexFormat.LE_SHORT, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(VortexFormat.LE_INT, (i % codesCap) * 4));
                        out.setAtIndex(VortexFormat.LE_SHORT, i, values.getAtIndex(VortexFormat.LE_SHORT, code % valuesCap));
                    }
                }
            }
            case 1 -> {
                if (fast) {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(VortexFormat.LE_INT, i * 4));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code));
                    }
                } else {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(VortexFormat.LE_INT, (i % codesCap) * 4));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code % valuesCap));
                    }
                }
            }
            default -> {
                if (fast) {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Integer.toUnsignedLong(codes.get(VortexFormat.LE_INT, i * 4));
                        MemorySegment.copy(values, code * elemSize, out, outOff, elemSize);
                    }
                } else {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Integer.toUnsignedLong(codes.get(VortexFormat.LE_INT, (i % codesCap) * 4));
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
