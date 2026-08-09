package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;

/// Lazy FastLanes-RLE-encoded [FloatArray]. See [LazyRleLongArray] for semantics.
///
/// @param dtype             logical element type
/// @param length            total logical row count
/// @param values            concatenated distinct values per chunk, as a little-endian
///                          `f32` segment of exactly `valuesLen` elements
/// @param indices           per-row local index table, as a `u8`/`u16` segment
/// @param wideIndices       `true` when `indices` holds `u16` elements, `false` for `u8`
/// @param valuesIdxOffsets  per-chunk values-pool start offsets
/// @param firstOffset       absolute origin of the values pool
/// @param valuesLen         total values pool length
/// @param numChunks         number of FastLanes chunks covered
/// @param offset            starting absolute position
@SuppressWarnings("java:S6218") // internal data carrier; the long[] offsets component is never compared.
public record LazyRleFloatArray(
        DType dtype, long length, MemorySegment values, MemorySegment indices, boolean wideIndices,
        long[] valuesIdxOffsets, long firstOffset, long valuesLen,
        int numChunks, int offset)
        implements FloatArray {

    @Override
    public float getFloat(long i) {
        int absRow = (int) (i + offset);
        int chunkIdx = absRow >>> RleArrays.FL_LOG2;
        int rowInChunk = absRow & RleArrays.FL_MASK;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        if (numChunkValues <= 1) {
            return numChunkValues == 1 ? values.getAtIndex(VortexFormat.LE_FLOAT, valueIdxOffset) : 0.0f;
        }
        int localIdx = RleArrays.localIndex(indices, wideIndices,
                (long) chunkIdx * RleArrays.FL_CHUNK_SIZE + rowInChunk);
        return values.getAtIndex(VortexFormat.LE_FLOAT,
                valueIdxOffset + Math.min(localIdx, numChunkValues - 1));
    }

    /// Bulk-decodes chunk by chunk into a fresh little-endian `f32` segment,
    /// with the constant-run fast path (`numChunkValues <= 1`) emitting each
    /// value once. See [LazyRleLongArray] for the chunk-walk rationale.
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `f32` segment of `length()` decoded elements
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n * 4L, 4);
        RleArrays.walkChunks(length, offset, numChunks,
                (chunkIdx, rowInChunk, end) -> processChunk(chunkIdx, rowInChunk, end, dst));
        return dst;
    }

    /// Writes one chunk's rows. The `u8`/`u16` index width is hoisted into its own loop
    /// (rather than tested per row) so each body stays uniform — see the CLAUDE.md hot-loop rule.
    private void processChunk(int chunkIdx, int rowInChunk, int end, MemorySegment dst) {
        long chunkBase = (long) chunkIdx * RleArrays.FL_CHUNK_SIZE;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        int maxIdx = numChunkValues - 1;
        // dstIdx tracks the logical output row; chunk 0 may start mid-chunk when offset != 0.
        long dstIdx = chunkBase + rowInChunk - offset;
        if (numChunkValues <= 1) {
            float v = numChunkValues == 1 ? values.getAtIndex(VortexFormat.LE_FLOAT, valueIdxOffset) : 0.0f;
            for (int r = rowInChunk; r < end; r++) {
                dst.setAtIndex(VortexFormat.LE_FLOAT, dstIdx++, v);
            }
        } else if (wideIndices) {
            for (int r = rowInChunk; r < end; r++) {
                int localIdx = Short.toUnsignedInt(indices.getAtIndex(VortexFormat.LE_SHORT, chunkBase + r));
                dst.setAtIndex(VortexFormat.LE_FLOAT, dstIdx++,
                        values.getAtIndex(VortexFormat.LE_FLOAT, valueIdxOffset + Math.min(localIdx, maxIdx)));
            }
        } else {
            for (int r = rowInChunk; r < end; r++) {
                int localIdx = Byte.toUnsignedInt(indices.get(ValueLayout.JAVA_BYTE, chunkBase + r));
                dst.setAtIndex(VortexFormat.LE_FLOAT, dstIdx++,
                        values.getAtIndex(VortexFormat.LE_FLOAT, valueIdxOffset + Math.min(localIdx, maxIdx)));
            }
        }
    }
}
