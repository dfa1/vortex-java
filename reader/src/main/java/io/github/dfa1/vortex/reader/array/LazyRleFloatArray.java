package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.VortexFormat;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Lazy FastLanes-RLE-encoded [FloatArray]. See [LazyRleLongArray] for semantics.
///
/// @param dtype             logical element type
/// @param length            total logical row count
/// @param values            concatenated distinct values per chunk
/// @param indices           per-row local index table
/// @param valuesIdxOffsets  per-chunk values-pool start offsets
/// @param firstOffset       absolute origin of the values pool
/// @param valuesLen         total values pool length
/// @param numChunks         number of FastLanes chunks covered
/// @param offset            starting absolute position
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record LazyRleFloatArray(
        DType dtype, long length, float[] values, int[] indices,
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
            return numChunkValues == 1 ? values[(int) valueIdxOffset] : 0.0f;
        }
        int localIdx = indices[chunkIdx * RleArrays.FL_CHUNK_SIZE + rowInChunk];
        if (localIdx >= numChunkValues) {
            localIdx = numChunkValues - 1;
        }
        return values[(int) valueIdxOffset + localIdx];
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

    private void processChunk(int chunkIdx, int rowInChunk, int end, MemorySegment dst) {
        int chunkBase = chunkIdx * RleArrays.FL_CHUNK_SIZE;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        // dstIdx tracks the logical output row; chunk 0 may start mid-chunk when offset != 0.
        long dstIdx = (long) chunkIdx * RleArrays.FL_CHUNK_SIZE + rowInChunk - offset;
        if (numChunkValues <= 1) {
            float v = numChunkValues == 1 ? values[(int) valueIdxOffset] : 0.0f;
            for (int r = rowInChunk; r < end; r++) {
                dst.setAtIndex(VortexFormat.LE_FLOAT, dstIdx++, v);
            }
        } else {
            for (int r = rowInChunk; r < end; r++) {
                int localIdx = indices[chunkBase + r];
                if (localIdx >= numChunkValues) {
                    localIdx = numChunkValues - 1;
                }
                dst.setAtIndex(VortexFormat.LE_FLOAT, dstIdx++, values[(int) valueIdxOffset + localIdx]);
            }
        }
    }
}
