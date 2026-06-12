package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.encoding.SegmentBroadcast;
import io.github.dfa1.vortex.proto.RunEndMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/// Read-only decoder for {@code vortex.runend}.
public final class RunEndEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public RunEndEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_RUNEND;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive p && !p.ptype().isFloating();
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null) {
            throw new VortexException(EncodingId.VORTEX_RUNEND, "missing metadata");
        }

        RunEndMetadata meta;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
            meta = RunEndMetadata.decode(metaSeg, 0, metaSeg.byteSize());
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
            Array valuesArr = ctx.decodeChild(1, ctx.dtype(), numRuns);
            return expandStrings(endsArr, (VarBinArray) valuesArr, endsPtype, numRuns, offset, n, ctx.dtype(), ctx.arena());
        }

        if (ctx.dtype() instanceof DType.Bool) {
            Array valuesArr = ctx.decodeChild(1, ctx.dtype(), numRuns);
            return expandBool(endsArr, (BoolArray) valuesArr, endsPtype, numRuns, offset, n, ctx.dtype(), ctx.arena());
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_RUNEND, "expected primitive dtype, got " + ctx.dtype());
        }
        PType valuePtype = p.ptype();

        return expand(ArraySegments.of(endsArr), ctx.decodeChildSegment(1, ctx.dtype(), numRuns),
                endsPtype, valuePtype, numRuns, offset, n, ctx.dtype(), ctx.arena());
    }

    private static Array expand(
            MemorySegment endsSeg, MemorySegment valuesSeg,
            PType endsPtype, PType valuePtype,
            long numRuns, long offset, long n,
            DType dtype, SegmentAllocator arena
    ) {
        MemorySegment out = arena.allocate(n * valuePtype.byteSize());
        switch (valuePtype) {
            case I8, U8 -> expandByte(endsSeg, valuesSeg, endsPtype, numRuns, offset, n, out);
            case I16, U16 -> expandShort(endsSeg, valuesSeg, endsPtype, numRuns, offset, n, out);
            case I32, U32 -> expandInt(endsSeg, valuesSeg, endsPtype, numRuns, offset, n, out);
            case I64, U64 -> expandLong(endsSeg, valuesSeg, endsPtype, numRuns, offset, n, out);
            default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "unsupported ptype " + valuePtype);
        }
        MemorySegment ro = out.asReadOnly();
        return switch (valuePtype) {
            case I64, U64 -> new LongArray(dtype, n, ro);
            case I32, U32 -> new IntArray(dtype, n, ro);
            case I16, U16 -> new ShortArray(dtype, n, ro);
            case I8, U8 -> new ByteArray(dtype, n, ro);
            default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "unsupported ptype " + valuePtype);
        };
    }

    private static void expandByte(MemorySegment endsSeg, MemorySegment valuesSeg,
            PType endsPtype, long numRuns, long offset, long n, MemorySegment out) {
        long endsCap = SegmentBroadcast.capacity(endsSeg, endsPtype.byteSize());
        long valCap = SegmentBroadcast.capacity(valuesSeg, 1);
        long logicalPos = 0L, outPos = 0L;
        for (long run = 0; run < numRuns && outPos < n; run++) {
            long runEnd = readUnsigned(endsSeg, run % endsCap, endsPtype);
            byte rawValue = valuesSeg.get(ValueLayout.JAVA_BYTE, run % valCap);
            long writeEnd = Math.min(runEnd, offset + n);
            for (long lp = Math.max(logicalPos, offset); lp < writeEnd; lp++, outPos++) {
                out.set(ValueLayout.JAVA_BYTE, outPos, rawValue);
            }
            logicalPos = runEnd;
        }
    }

    private static void expandShort(MemorySegment endsSeg, MemorySegment valuesSeg,
            PType endsPtype, long numRuns, long offset, long n, MemorySegment out) {
        long endsCap = SegmentBroadcast.capacity(endsSeg, endsPtype.byteSize());
        long valCap = SegmentBroadcast.capacity(valuesSeg, 2);
        long logicalPos = 0L, outPos = 0L;
        for (long run = 0; run < numRuns && outPos < n; run++) {
            long runEnd = readUnsigned(endsSeg, run % endsCap, endsPtype);
            short rawValue = valuesSeg.get(PTypeIO.LE_SHORT, (run % valCap) * 2);
            long writeEnd = Math.min(runEnd, offset + n);
            for (long lp = Math.max(logicalPos, offset); lp < writeEnd; lp++, outPos++) {
                out.set(PTypeIO.LE_SHORT, outPos * 2, rawValue);
            }
            logicalPos = runEnd;
        }
    }

    private static void expandInt(MemorySegment endsSeg, MemorySegment valuesSeg,
            PType endsPtype, long numRuns, long offset, long n, MemorySegment out) {
        long endsCap = SegmentBroadcast.capacity(endsSeg, endsPtype.byteSize());
        long valCap = SegmentBroadcast.capacity(valuesSeg, 4);
        long logicalPos = 0L, outPos = 0L;
        for (long run = 0; run < numRuns && outPos < n; run++) {
            long runEnd = readUnsigned(endsSeg, run % endsCap, endsPtype);
            int rawValue = valuesSeg.get(PTypeIO.LE_INT, (run % valCap) * 4);
            long writeEnd = Math.min(runEnd, offset + n);
            for (long lp = Math.max(logicalPos, offset); lp < writeEnd; lp++, outPos++) {
                out.set(PTypeIO.LE_INT, outPos * 4, rawValue);
            }
            logicalPos = runEnd;
        }
    }

    private static void expandLong(MemorySegment endsSeg, MemorySegment valuesSeg,
            PType endsPtype, long numRuns, long offset, long n, MemorySegment out) {
        long endsCap = SegmentBroadcast.capacity(endsSeg, endsPtype.byteSize());
        long valCap = SegmentBroadcast.capacity(valuesSeg, 8);
        long logicalPos = 0L, outPos = 0L;
        for (long run = 0; run < numRuns && outPos < n; run++) {
            long runEnd = readUnsigned(endsSeg, run % endsCap, endsPtype);
            long rawValue = valuesSeg.get(PTypeIO.LE_LONG, (run % valCap) * 8);
            long writeEnd = Math.min(runEnd, offset + n);
            for (long lp = Math.max(logicalPos, offset); lp < writeEnd; lp++, outPos++) {
                out.set(PTypeIO.LE_LONG, outPos * 8, rawValue);
            }
            logicalPos = runEnd;
        }
    }

    private static Array expandBool(
            Array endsArr, BoolArray valuesArr,
            PType endsPtype, long numRuns, long offset, long n,
            DType dtype, SegmentAllocator arena
    ) {
        MemorySegment endsSeg = ArraySegments.of(endsArr);
        long endsCap = SegmentBroadcast.capacity(endsSeg, endsPtype.byteSize());
        long numBytes = (n + 7) >>> 3;
        MemorySegment out = arena.allocate(numBytes);

        long outIdx = 0;
        long logicalPos = 0;
        for (long run = 0; run < numRuns && outIdx < n; run++) {
            long runEnd = readUnsigned(endsSeg, run % endsCap, endsPtype);
            boolean val = valuesArr.getBoolean(run);
            long lo = Math.max(logicalPos, offset);
            long hi = Math.min(runEnd, offset + n);
            for (long lp = lo; lp < hi; lp++, outIdx++) {
                if (val) {
                    long byteIdx = outIdx >>> 3;
                    byte cur = out.get(ValueLayout.JAVA_BYTE, byteIdx);
                    out.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) (cur | (1 << (outIdx & 7))));
                }
            }
            logicalPos = runEnd;
        }
        return new BoolArray(dtype, n, out.asReadOnly());
    }

    private static Array expandStrings(
            Array endsArr, VarBinArray valuesArr,
            PType endsPtype, long numRuns, long offset, long n,
            DType dtype, SegmentAllocator arena
    ) {
        MemorySegment endsSeg = ArraySegments.of(endsArr);
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

        return new VarBinArray(dtype, n, outBytes.asReadOnly(), outOffsets.asReadOnly(), PType.I32);
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
