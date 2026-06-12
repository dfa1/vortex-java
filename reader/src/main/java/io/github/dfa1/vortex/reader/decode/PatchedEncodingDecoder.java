package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.encoding.SegmentBroadcast;
import io.github.dfa1.vortex.proto.PatchedMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Read-only decoder for {@code vortex.patched}.
public final class PatchedEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public PatchedEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PATCHED;
    }

    @Override
    public boolean accepts(DType dtype) {
        return false;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null || !rawMeta.hasRemaining()) {
            throw new VortexException(EncodingId.VORTEX_PATCHED, "missing metadata");
        }

        long nPatches;
        long nLanes;
        long offset;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
            PatchedMetadata meta = PatchedMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            nPatches = Integer.toUnsignedLong(meta.n_patches());
            nLanes = Integer.toUnsignedLong(meta.n_lanes());
            offset = Integer.toUnsignedLong(meta.offset());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_PATCHED, "invalid metadata", e);
        }

        if (nLanes == 0) {
            throw new VortexException(EncodingId.VORTEX_PATCHED, "n_lanes must be > 0");
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_PATCHED,
                    "expected primitive dtype, got " + ctx.dtype());
        }

        PType ptype = p.ptype();
        long n = ctx.rowCount();
        long nChunks = (n + offset + 1023) / 1024;
        int elemBytes = ptype.byteSize();

        MemorySegment innerSeg = ctx.decodeChildSegment(0, ctx.dtype(), n);
        MemorySegment laneOffsetsSeg = ctx.decodeChildSegment(1,
                new DType.Primitive(PType.U32, false), nChunks * nLanes + 1);
        MemorySegment patchIndicesSeg = ctx.decodeChildSegment(2,
                new DType.Primitive(PType.U16, false), nPatches);
        MemorySegment patchValuesSeg = ctx.decodeChildSegment(3, ctx.dtype(), nPatches);

        MemorySegment out = ctx.arena().allocate(n * elemBytes);
        SegmentBroadcast.broadcastCopy(innerSeg, out, n, elemBytes);

        if (nPatches > 0) {
            applyPatches(out, n, nChunks, nLanes, offset, elemBytes,
                    laneOffsetsSeg, patchIndicesSeg, patchValuesSeg);
        }

        return switch (ptype) {
            case I8, U8 -> new ByteArray(ctx.dtype(), n, out);
            case I16, U16 -> new ShortArray(ctx.dtype(), n, out);
            case I32, U32 -> new IntArray(ctx.dtype(), n, out);
            case I64, U64 -> new LongArray(ctx.dtype(), n, out);
            case F32 -> new FloatArray(ctx.dtype(), n, out);
            case F64 -> new DoubleArray(ctx.dtype(), n, out);
            default -> throw new VortexException(EncodingId.VORTEX_PATCHED,
                    "unsupported ptype: " + ptype);
        };
    }

    private static void applyPatches(
            MemorySegment out, long n, long nChunks, long nLanes, long offset, int elemBytes,
            MemorySegment laneOffsets, MemorySegment patchIndices, MemorySegment patchValues
    ) {
        long laneCap = SegmentBroadcast.capacity(laneOffsets, 4);
        long idxCap = SegmentBroadcast.capacity(patchIndices, 2);
        long valCap = SegmentBroadcast.capacity(patchValues, elemBytes);
        for (long chunk = 0; chunk < nChunks; chunk++) {
            long start = Integer.toUnsignedLong(
                    laneOffsets.getAtIndex(PTypeIO.LE_INT, (chunk * nLanes) % laneCap));
            long stop = Integer.toUnsignedLong(
                    laneOffsets.getAtIndex(PTypeIO.LE_INT, (chunk * nLanes + nLanes) % laneCap));

            for (long i = start; i < stop; i++) {
                long physicalIdx = chunk * 1024
                        + Short.toUnsignedLong(patchIndices.getAtIndex(PTypeIO.LE_SHORT, i % idxCap));
                if (physicalIdx < offset || physicalIdx >= offset + n) {
                    continue;
                }
                long outputIdx = physicalIdx - offset;
                MemorySegment.copy(patchValues, (i % valCap) * elemBytes, out, outputIdx * elemBytes, elemBytes);
            }
        }
    }
}
