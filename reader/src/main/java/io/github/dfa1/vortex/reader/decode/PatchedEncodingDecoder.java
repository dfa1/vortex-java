package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoPatchedMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.patched`.
public final class PatchedEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PATCHED;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            throw new VortexException(EncodingId.VORTEX_PATCHED, "missing metadata");
        }

        long nPatches;
        long nLanes;
        long offset;
        try {
            MemorySegment metaSeg = rawMeta;
            ProtoPatchedMetadata meta = ProtoPatchedMetadata.decode(metaSeg, 0, metaSeg.byteSize());
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
                DType.U32, nChunks * nLanes + 1);
        MemorySegment patchIndicesSeg = ctx.decodeChildSegment(2,
                DType.U16, nPatches);
        MemorySegment patchValuesSeg = ctx.decodeChildSegment(3, ctx.dtype(), nPatches);

        MemorySegment out = patchedOutput(ctx, innerSeg, n, elemBytes, nPatches,
                nChunks, nLanes, offset, laneOffsetsSeg, patchIndicesSeg, patchValuesSeg);

        return switch (ptype) {
            case I8, U8 -> new MaterializedByteArray(ctx.dtype(), n, out);
            case I16, U16 -> new MaterializedShortArray(ctx.dtype(), n, out);
            case I32, U32 -> new MaterializedIntArray(ctx.dtype(), n, out);
            case I64, U64 -> new MaterializedLongArray(ctx.dtype(), n, out);
            case F32 -> new MaterializedFloatArray(ctx.dtype(), n, out);
            case F64 -> new MaterializedDoubleArray(ctx.dtype(), n, out);
            default -> throw new VortexException(EncodingId.VORTEX_PATCHED,
                    "unsupported ptype: " + ptype);
        };
    }

    /// Produces the patched values buffer.
    ///
    /// With no patches to apply, the result is byte-for-byte the inner child, so allocating
    /// `n * elemBytes` and copying into it produces a duplicate and nothing else — the inner
    /// segment is already arena-lifetime, having just come from `decodeChildSegment`. A
    /// zero-patch `vortex.patched` node is not exotic: a bitpacked column whose exception list
    /// happens to be empty for a chunk still round-trips through this encoding.
    ///
    /// The copy is still required when the inner child holds fewer than `n` elements — the
    /// `ConstantEncoding` fan-out that [SegmentBroadcast#broadcastCopy] exists for — so the
    /// alias is taken only when the child covers every row. It is sliced to exactly `n`
    /// elements so the `Materialized*` accessors keep their `length == elementCount` fast path
    /// even when the child buffer runs long.
    ///
    /// @param ctx             decode context (allocation arena)
    /// @param innerSeg        decoded inner child
    /// @param n               logical row count
    /// @param elemBytes       value element width
    /// @param nPatches        number of patches declared by the metadata
    /// @param nChunks         number of 1024-row chunks spanned
    /// @param nLanes          lanes per chunk
    /// @param offset          starting absolute position
    /// @param laneOffsetsSeg  per-chunk lane offsets
    /// @param patchIndicesSeg per-patch in-chunk indices
    /// @param patchValuesSeg  per-patch replacement values
    /// @return the values buffer, aliased to `innerSeg` when there is nothing to patch
    private static MemorySegment patchedOutput(DecodeContext ctx, MemorySegment innerSeg, long n, int elemBytes,
            long nPatches, long nChunks, long nLanes, long offset,
            MemorySegment laneOffsetsSeg, MemorySegment patchIndicesSeg, MemorySegment patchValuesSeg) {
        if (nPatches == 0 && SegmentBroadcast.capacity(innerSeg, elemBytes) >= n) {
            return innerSeg.asSlice(0, n * elemBytes).asReadOnly();
        }
        MemorySegment out = ctx.arena().allocate(n * elemBytes);
        SegmentBroadcast.broadcastCopy(innerSeg, out, n, elemBytes);
        if (nPatches > 0) {
            applyPatches(out, n, nChunks, nLanes, offset, elemBytes,
                    laneOffsetsSeg, patchIndicesSeg, patchValuesSeg);
        }
        return out;
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
                    laneOffsets.getAtIndex(VortexFormat.LE_INT, (chunk * nLanes) % laneCap));
            long stop = Integer.toUnsignedLong(
                    laneOffsets.getAtIndex(VortexFormat.LE_INT, (chunk * nLanes + nLanes) % laneCap));

            for (long i = start; i < stop; i++) {
                long physicalIdx = chunk * 1024
                        + Short.toUnsignedLong(patchIndices.getAtIndex(VortexFormat.LE_SHORT, i % idxCap));
                if (physicalIdx < offset || physicalIdx >= offset + n) {
                    continue;
                }
                long outputIdx = physicalIdx - offset;
                MemorySegment.copy(patchValues, (i % valCap) * elemBytes, out, outputIdx * elemBytes, elemBytes);
            }
        }
    }
}
