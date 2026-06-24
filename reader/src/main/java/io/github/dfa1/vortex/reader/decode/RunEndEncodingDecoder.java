package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoRunEndMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndBoolArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndByteArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndIntArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndLongArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndShortArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `vortex.runend`.
public final class RunEndEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public RunEndEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_RUNEND;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        if (rawMeta == null) {
            throw new VortexException(EncodingId.VORTEX_RUNEND, "missing metadata");
        }

        ProtoRunEndMetadata meta;
        try {
            MemorySegment metaSeg = rawMeta;
            meta = ProtoRunEndMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_RUNEND, "invalid metadata", e);
        }

        PType endsPtype = PType.fromOrdinal(meta.ends_ptype().value());
        long numRuns = meta.num_runs();
        long offset = meta.offset();

        long n = ctx.rowCount();
        DType endsDtype = new DType.Primitive(endsPtype, false);
        Array endsArr = ctx.decodeChild(0, endsDtype, numRuns);

        if (ctx.dtype() instanceof DType.Utf8 || ctx.dtype() instanceof DType.Binary) {
            VarBinArray valuesArr = (VarBinArray) ctx.decodeChild(1, ctx.dtype(), numRuns);
            MemorySegment endsSeg = ctx.materialize(endsArr);
            return expandStrings(endsSeg, VarBinArray.toOffsetMode(valuesArr, ctx.arena()), endsPtype, numRuns, offset, n, ctx.dtype(), ctx.arena());
        }

        if (ctx.dtype() instanceof DType.Bool) {
            Array valuesArr = ctx.decodeChild(1, ctx.dtype(), numRuns);
            Array valuesData = valuesArr instanceof MaskedArray m ? m.inner() : valuesArr;
            Array endsData = endsArr instanceof MaskedArray m ? m.inner() : endsArr;
            return new LazyRunEndBoolArray(ctx.dtype(), n, (BoolArray) valuesData, endsData, offset);
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_RUNEND, "expected primitive dtype, got " + ctx.dtype());
        }
        PType valuePtype = p.ptype();

        // Lazy path: wrap values + ends without expanding into an n-sized buffer.
        // VarBin keeps the eager path above — offset rebasing doesn't trivially
        // express as binary-search-on-read.
        Array valuesArr = ctx.decodeChild(1, ctx.dtype(), numRuns);
        Array valuesData = valuesArr instanceof MaskedArray m ? m.inner() : valuesArr;
        Array endsData = endsArr instanceof MaskedArray m ? m.inner() : endsArr;
        return switch (valuePtype) {
            case I64, U64 -> new LazyRunEndLongArray(ctx.dtype(), n, (LongArray) valuesData, endsData, offset);
            case I32, U32 -> new LazyRunEndIntArray(ctx.dtype(), n, (IntArray) valuesData, endsData, offset);
            case I16, U16 -> new LazyRunEndShortArray(ctx.dtype(), n, (ShortArray) valuesData, endsData, offset);
            case I8, U8 -> new LazyRunEndByteArray(ctx.dtype(), n, (ByteArray) valuesData, endsData, offset);
            default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "unsupported ptype " + valuePtype);
        };
    }

    private static Array expandStrings(
            MemorySegment endsSeg, VarBinArray.OffsetMode valuesArr,
            PType endsPtype, long numRuns, long offset, long n,
            DType dtype, SegmentAllocator arena
    ) {
        long endsCap = SegmentBroadcast.capacity(endsSeg, endsPtype.byteSize());
        MemorySegment valBytes = valuesArr.bytesSegment();
        MemorySegment valOffsets = valuesArr.offsetsSegment();
        PType valOffPtype = valuesArr.offsetsPtype();

        long totalBytes = 0;
        long logicalPos = 0;
        for (long run = 0; run < numRuns; run++) {
            long runEnd = readUnsigned(endsSeg, run % endsCap, endsPtype);
            long lo = Math.max(logicalPos, offset);
            long hi = Math.min(runEnd, offset + n);
            long count = Math.max(0, hi - lo);
            long strLen = readVarBinOffset(valOffsets, run + 1, valOffPtype)
                                  - readVarBinOffset(valOffsets, run, valOffPtype);
            totalBytes += count * strLen;
            logicalPos = runEnd;
        }

        MemorySegment outBytes = arena.allocate(totalBytes > 0 ? totalBytes : 1);
        MemorySegment outOffsets = arena.allocate((n + 1) * 4L, 4);
        outOffsets.setAtIndex(PTypeIO.LE_INT, 0, 0);

        long bytePos = 0;
        long outIdx = 0;
        logicalPos = 0;
        for (long run = 0; run < numRuns && outIdx < n; run++) {
            long runEnd = readUnsigned(endsSeg, run % endsCap, endsPtype);
            long lo = Math.max(logicalPos, offset);
            long hi = Math.min(runEnd, offset + n);
            if (hi > lo) {
                long strStart = readVarBinOffset(valOffsets, run, valOffPtype);
                long strEnd = readVarBinOffset(valOffsets, run + 1, valOffPtype);
                long strLen = strEnd - strStart;
                for (long lp = lo; lp < hi; lp++, outIdx++) {
                    if (strLen > 0) {
                        MemorySegment.copy(valBytes, strStart, outBytes, bytePos, strLen);
                        bytePos += strLen;
                    }
                    outOffsets.setAtIndex(PTypeIO.LE_INT, outIdx + 1, (int) bytePos);
                }
            }
            logicalPos = runEnd;
        }

        return new VarBinArray.OffsetMode(dtype, n, outBytes.asReadOnly(), outOffsets.asReadOnly(), PType.I32);
    }

    private static long readUnsigned(MemorySegment seg, long i, PType ptype) {
        return switch (ptype) {
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
            case U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, i * 2));
            case U32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, i * 4));
            case U64 -> seg.get(PTypeIO.LE_LONG, i * 8);
            default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "non-unsigned ends ptype " + ptype);
        };
    }

    private static long readVarBinOffset(MemorySegment seg, long i, PType ptype) {
        return switch (ptype) {
            case I32, U32 -> Integer.toUnsignedLong(seg.getAtIndex(PTypeIO.LE_INT, i));
            case I64, U64 -> seg.getAtIndex(PTypeIO.LE_LONG, i);
            default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "unsupported offset ptype " + ptype);
        };
    }
}
