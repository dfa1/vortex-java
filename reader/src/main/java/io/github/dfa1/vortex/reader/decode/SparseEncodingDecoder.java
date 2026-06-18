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
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazySparseBoolArray;
import io.github.dfa1.vortex.reader.array.LazySparseByteArray;
import io.github.dfa1.vortex.reader.array.LazySparseDoubleArray;
import io.github.dfa1.vortex.reader.array.LazySparseFloatArray;
import io.github.dfa1.vortex.reader.array.LazySparseIntArray;
import io.github.dfa1.vortex.reader.array.LazySparseLongArray;
import io.github.dfa1.vortex.reader.array.LazySparseShortArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/// Read-only decoder for `vortex.sparse`.
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
            DType indicesDtype = new DType.Primitive(indicesPtype, false);
            Array patchIndices = ctx.decodeChild(0, indicesDtype, numPatches);
            Array patchValues = ctx.decodeChild(1, ctx.dtype(), numPatches);
            Array idxData = patchIndices instanceof MaskedArray m ? m.inner() : patchIndices;
            Array valData = patchValues instanceof MaskedArray m ? m.inner() : patchValues;
            return new LazySparseBoolArray(ctx.dtype(), n, false, (BoolArray) valData, idxData, offset);
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
        long fillBits = scalarToLong(fillScalar);

        // Lazy path: keep fill bits + decoded patches; no n-sized buffer allocated.
        // When numPatches == 0 we still decode zero-length children so the record's
        // patchValues.length() and findPatch can rely on real (empty) Array instances.
        DType indicesDtype = new DType.Primitive(indicesPtype, false);
        Array patchIndices = ctx.decodeChild(0, indicesDtype, numPatches);
        Array patchValues = ctx.decodeChild(1, ctx.dtype(), numPatches);
        Array idxData = patchIndices instanceof MaskedArray m ? m.inner() : patchIndices;
        Array valData = patchValues instanceof MaskedArray m ? m.inner() : patchValues;

        return switch (valuePtype) {
            case I64, U64 -> new LazySparseLongArray(ctx.dtype(), n, fillBits,
                    (LongArray) valData, idxData, offset);
            case I32, U32 -> new LazySparseIntArray(ctx.dtype(), n, (int) fillBits,
                    (IntArray) valData, idxData, offset);
            case F64 -> new LazySparseDoubleArray(ctx.dtype(), n, Double.longBitsToDouble(fillBits),
                    (DoubleArray) valData, idxData, offset);
            case F32 -> new LazySparseFloatArray(ctx.dtype(), n, Float.intBitsToFloat((int) fillBits),
                    (FloatArray) valData, idxData, offset);
            case I16 -> new LazySparseShortArray(ctx.dtype(), n, (short) fillBits, (short) fillBits,
                    (ShortArray) valData, idxData, offset);
            case U16 -> new LazySparseShortArray(ctx.dtype(), n, (short) fillBits, (int) (fillBits & 0xFFFFL),
                    (ShortArray) valData, idxData, offset);
            case I8 -> new LazySparseByteArray(ctx.dtype(), n, (byte) fillBits, (byte) fillBits,
                    (ByteArray) valData, idxData, offset);
            case U8 -> new LazySparseByteArray(ctx.dtype(), n, (byte) fillBits, (int) (fillBits & 0xFFL),
                    (ByteArray) valData, idxData, offset);
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype " + valuePtype);
        };
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
        VarBinArray rawValues = (VarBinArray) ctx.decodeChild(1, ctx.dtype(), numPatches);
        VarBinArray.OffsetMode varBin = VarBinArray.toOffsetMode(rawValues, ctx.arena());
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

    private static long readUnsignedIdx(MemorySegment seg, long off, PType ptype) {
        return switch (ptype) {
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, off));
            case U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, off));
            case U32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, off));
            case U64 -> seg.get(PTypeIO.LE_LONG, off);
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "non-unsigned index ptype " + ptype);
        };
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
