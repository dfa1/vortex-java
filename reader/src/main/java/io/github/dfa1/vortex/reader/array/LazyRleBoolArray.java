package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

/// Lazy FastLanes-RLE-encoded [BoolArray].
///
/// Mirrors [LazyRleLongArray] — see its doc for the chunk/values/indices layout — with `values`
/// a [BoolArray] (at most 2 distinct values per 1024-row chunk) instead of a numeric array.
///
/// @param dtype             logical Bool type
/// @param length            total logical row count
/// @param values            concatenated distinct values per chunk
/// @param indices           per-row local index table; length `numChunks * 1024`
/// @param valuesIdxOffsets  per-chunk values-pool start offsets; length `numChunks`
/// @param firstOffset       absolute origin of the values pool
/// @param valuesLen         total values pool length
/// @param numChunks         number of FastLanes chunks covered
/// @param offset            starting absolute position; logical row `i` maps to
///                          absolute `i + offset`
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record LazyRleBoolArray(
        DType dtype, long length, BoolArray values, int[] indices,
        long[] valuesIdxOffsets, long firstOffset, long valuesLen,
        int numChunks, int offset)
        implements BoolArray {

    @Override
    public boolean getBoolean(long i) {
        int absRow = (int) (i + offset);
        int chunkIdx = absRow >>> RleArrays.FL_LOG2;
        int rowInChunk = absRow & RleArrays.FL_MASK;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        if (numChunkValues <= 1) {
            return numChunkValues == 1 && values.getBoolean(valueIdxOffset);
        }
        int localIdx = indices[chunkIdx * RleArrays.FL_CHUNK_SIZE + rowInChunk];
        if (localIdx >= numChunkValues) {
            localIdx = numChunkValues - 1;
        }
        return values.getBoolean(valueIdxOffset + localIdx);
    }

    @Override
    public void forEachBoolean(BooleanConsumer c) {
        RleArrays.walkChunks(length, offset, numChunks,
                (chunkIdx, rowInChunk, end) -> processChunk(chunkIdx, rowInChunk, end, c));
    }

    private void processChunk(int chunkIdx, int rowInChunk, int end, BooleanConsumer c) {
        int chunkBase = chunkIdx * RleArrays.FL_CHUNK_SIZE;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        if (numChunkValues <= 1) {
            boolean v = numChunkValues == 1 && values.getBoolean(valueIdxOffset);
            for (int r = rowInChunk; r < end; r++) {
                c.accept(v);
            }
        } else {
            for (int r = rowInChunk; r < end; r++) {
                int localIdx = indices[chunkBase + r];
                if (localIdx >= numChunkValues) {
                    localIdx = numChunkValues - 1;
                }
                c.accept(values.getBoolean(valueIdxOffset + localIdx));
            }
        }
    }
}
