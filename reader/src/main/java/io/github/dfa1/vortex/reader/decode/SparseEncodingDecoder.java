package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.PatchesMetadata;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.proto.SparseMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Read-only decoder for {@code vortex.sparse}.
public final class SparseEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public SparseEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_SPARSE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null || !rawMeta.hasRemaining()) {
            throw new VortexException(EncodingId.VORTEX_SPARSE, "missing metadata");
        }
        SparseMetadata sparseMeta;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
            sparseMeta = SparseMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_SPARSE, "invalid metadata", e);
        }

        PatchesMetadata patches = sparseMeta.patches();
        long numPatches = patches.len();
        long offset = patches.offset();
        PType indicesPtype = PType.fromOrdinal(patches.indices_ptype().value());

        long n = ctx.rowCount();

        if (ctx.dtype() instanceof DType.Utf8 || ctx.dtype() instanceof DType.Binary) {
            return decodeVarBin(ctx, n, numPatches, offset, indicesPtype);
        }

        if (ctx.dtype() instanceof DType.Bool) {
            return decodeBool(ctx, n, numPatches, offset, indicesPtype);
        }

        if (!(ctx.dtype() instanceof DType.Primitive)) {
            throw new VortexException(EncodingId.VORTEX_SPARSE, "expected primitive dtype, got " + ctx.dtype());
        }
        PType valuePtype = ((DType.Primitive) ctx.dtype()).ptype();

        MemorySegment fillBuf = ctx.buffer(0);
        ScalarValue fillScalar;
        try {
            fillScalar = ScalarValue.decode(fillBuf, 0, fillBuf.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_SPARSE, "invalid fill value", e);
        }

        int elemBytes = valuePtype.byteSize();
        MemorySegment out = ctx.arena().allocate(n * elemBytes);
        fillSegment(out, n, valuePtype, fillScalar);

        if (numPatches > 0) {
            DType indicesDtype = new DType.Primitive(indicesPtype, false);
            applyPatches(out, n, valuePtype,
                    ctx.decodeChildSegment(0, indicesDtype, numPatches),
                    ctx.decodeChildSegment(1, ctx.dtype(), numPatches),
                    indicesPtype, numPatches, offset);
        }

        return switch (valuePtype) {
            case I64, U64 -> new MaterializedLongArray(ctx.dtype(), n, out);
            case I32, U32 -> new MaterializedIntArray(ctx.dtype(), n, out);
            case F64 -> new MaterializedDoubleArray(ctx.dtype(), n, out);
            case F32 -> new MaterializedFloatArray(ctx.dtype(), n, out);
            case I16, U16 -> new MaterializedShortArray(ctx.dtype(), n, out);
            case I8, U8 -> new MaterializedByteArray(ctx.dtype(), n, out);
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype " + valuePtype);
        };
    }

    private static Array decodeBool(
            DecodeContext ctx, long n, long numPatches, long offset, PType indicesPtype
    ) {
        long numBytes = (n + 7) >>> 3;
        MemorySegment out = ctx.arena().allocate(numBytes);
        if (numPatches > 0) {
            DType indicesDtype = new DType.Primitive(indicesPtype, false);
            MemorySegment idxSeg = ctx.decodeChildSegment(0, indicesDtype, numPatches);
            BoolArray bools = (BoolArray) ctx.decodeChild(1, ctx.dtype(), numPatches);
            int idxBytes = indicesPtype.byteSize();
            for (long i = 0; i < numPatches; i++) {
                if (bools.getBoolean(i)) {
                    long pos = readUnsignedIdx(idxSeg, SegmentBroadcast.elementOffset(idxSeg, i, idxBytes), indicesPtype) - offset;
                    long byteIdx = pos >>> 3;
                    byte cur = out.get(ValueLayout.JAVA_BYTE, byteIdx);
                    out.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) (cur | (1 << (pos & 7))));
                }
            }
        }
        return new MaterializedBoolArray(ctx.dtype(), n, out);
    }

    private static Array decodeVarBin(
            DecodeContext ctx, long n, long numPatches, long offset, PType indicesPtype
    ) {
        MemorySegment outOffsets = ctx.arena().allocate((n + 1) * 4L, 4);
        if (numPatches == 0) {
            MemorySegment outBytes = ctx.arena().allocate(1);
            return new VarBinArray.OffsetMode(ctx.dtype(), n, outBytes, outOffsets, PType.I32);
        }

        DType indicesDtype = new DType.Primitive(indicesPtype, false);
        MemorySegment idxSeg = ctx.decodeChildSegment(0, indicesDtype, numPatches);
        VarBinArray.OffsetMode varBin = (VarBinArray.OffsetMode) ctx.decodeChild(1, ctx.dtype(), numPatches);
        MemorySegment valBytes = varBin.bytesSegment();
        MemorySegment valOffsets = varBin.offsetsSegment();
        PType valOffPtype = varBin.offsetsPtype();

        int idxBytes = indicesPtype.byteSize();
        long totalBytes = 0;
        for (long i = 0; i < numPatches; i++) {
            totalBytes += readVarBinOffset(valOffsets, i + 1, valOffPtype)
                                  - readVarBinOffset(valOffsets, i, valOffPtype);
        }

        MemorySegment outBytes = ctx.arena().allocate(Math.max(1, totalBytes));
        long patchCursor = 0;
        long bytePos = 0;
        for (long pos = 0; pos < n; pos++) {
            if (patchCursor < numPatches) {
                long patchPos = readUnsignedIdx(idxSeg, SegmentBroadcast.elementOffset(idxSeg, patchCursor, idxBytes), indicesPtype) - offset;
                if (patchPos == pos) {
                    long strStart = readVarBinOffset(valOffsets, patchCursor, valOffPtype);
                    long strEnd = readVarBinOffset(valOffsets, patchCursor + 1, valOffPtype);
                    long strLen = strEnd - strStart;
                    if (strLen > 0) {
                        MemorySegment.copy(valBytes, strStart, outBytes, bytePos, strLen);
                        bytePos += strLen;
                    }
                    patchCursor++;
                }
            }
            outOffsets.setAtIndex(PTypeIO.LE_INT, pos + 1, (int) bytePos);
        }

        return new VarBinArray.OffsetMode(ctx.dtype(), n, outBytes, outOffsets, PType.I32);
    }

    private static long readVarBinOffset(MemorySegment seg, long i, PType ptype) {
        return switch (ptype) {
            case I32, U32 -> Integer.toUnsignedLong(seg.getAtIndex(PTypeIO.LE_INT, i));
            case I64, U64 -> seg.getAtIndex(PTypeIO.LE_LONG, i);
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported offset ptype " + ptype);
        };
    }

    private static void fillSegment(MemorySegment out, long n, PType ptype, ScalarValue scalar) {
        long fillLong = scalarToLong(scalar);
        ByteBuffer bb = out.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        for (long i = 0; i < n; i++) {
            writeElem(bb, ptype, fillLong);
        }
    }

    private static void applyPatches(
            MemorySegment out, long n, PType valuePtype,
            MemorySegment idxSeg, MemorySegment valSeg,
            PType idxPtype, long numPatches, long offset
    ) {
        int elemBytes = valuePtype.byteSize();
        int idxBytes = idxPtype.byteSize();
        ByteBuffer outBuf = out.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        for (long i = 0; i < numPatches; i++) {
            long idx = readUnsignedIdx(idxSeg, SegmentBroadcast.elementOffset(idxSeg, i, idxBytes), idxPtype) - offset;
            if (idx < 0 || idx >= n) {
                throw new VortexException(EncodingId.VORTEX_SPARSE,
                        "patch index " + idx + " out of range [0," + n + ")");
            }
            long val = readElem(valSeg, SegmentBroadcast.elementOffset(valSeg, i, elemBytes), valuePtype);
            outBuf.position((int) (idx * elemBytes));
            writeElem(outBuf, valuePtype, val);
        }
    }

    private static long readUnsignedIdx(MemorySegment seg, long off, PType ptype) {
        return switch (ptype) {
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, off));
            case U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, off));
            case U32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, off));
            case U64 -> seg.get(PTypeIO.LE_LONG, off);
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "non-unsigned index ptype " + ptype);
        };
    }

    private static long readElem(MemorySegment seg, long off, PType ptype) {
        return switch (ptype) {
            case I8, U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, off));
            case I16, U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, off));
            case I32, U32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, off));
            case I64, U64, F32, F64 -> seg.get(PTypeIO.LE_LONG, off);
            default -> throw new UnsupportedOperationException("vortex.sparse: unsupported ptype " + ptype);
        };
    }

    private static void writeElem(ByteBuffer bb, PType ptype, long bits) {
        switch (ptype) {
            case I8, U8 -> bb.put((byte) bits);
            case I16, U16 -> bb.putShort((short) bits);
            case I32, U32 -> bb.putInt((int) bits);
            case I64, U64, F32, F64 -> bb.putLong(bits);
            default -> throw new UnsupportedOperationException("vortex.sparse: unsupported ptype " + ptype);
        }
    }

    private static long scalarToLong(ScalarValue scalar) {
        if (scalar.int64_value() != null) {
            return scalar.int64_value();
        }
        if (scalar.uint64_value() != null) {
            return scalar.uint64_value();
        }
        if (scalar.f32_value() != null) {
            return Float.floatToRawIntBits(scalar.f32_value());
        }
        if (scalar.f64_value() != null) {
            return Double.doubleToRawLongBits(scalar.f64_value());
        }
        return 0L;
    }
}
