package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Lazy FastLanes-RLE-encoded [LongArray].
///
/// FastLanes RLE encodes 1024-row chunks of values + indices. For each chunk the
/// distinct values are concatenated into `values`; the per-row local index
/// into the chunk's value range lives in `indices`; `valuesIdxOffsets`
/// gives the chunk-start offset into the global values pool. `getLong(i)`
/// resolves `values[valuesIdxOffsets[chunkIdx(i)] + clampedLocalIdx(...)]`.
///
/// `forEachLong` / `fold` iterate chunk-by-chunk so the constant-run
/// fast path (`numChunkValues <= 1`) emits each value once with a tight inner loop.
///
/// @param dtype             logical element type
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
public record LazyRleLongArray(
        DType dtype, long length, long[] values, int[] indices,
        long[] valuesIdxOffsets, long firstOffset, long valuesLen,
        int numChunks, int offset)
        implements LongArray {

    @Override
    public long getLong(long i) {
        int absRow = (int) (i + offset);
        int chunkIdx = absRow >>> RleArrays.FL_LOG2;
        int rowInChunk = absRow & RleArrays.FL_MASK;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        if (numChunkValues <= 1) {
            return numChunkValues == 1 ? values[(int) valueIdxOffset] : 0L;
        }
        int localIdx = indices[chunkIdx * RleArrays.FL_CHUNK_SIZE + rowInChunk];
        if (localIdx >= numChunkValues) {
            localIdx = numChunkValues - 1;
        }
        return values[(int) valueIdxOffset + localIdx];
    }

    @Override
    public void forEachLong(LongConsumer c) {
        RleArrays.walkChunks(length, offset, numChunks,
                (chunkIdx, rowInChunk, end) -> processChunk(chunkIdx, rowInChunk, end, c));
    }

    private void processChunk(int chunkIdx, int rowInChunk, int end, LongConsumer c) {
        int chunkBase = chunkIdx * RleArrays.FL_CHUNK_SIZE;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        if (numChunkValues <= 1) {
            long v = numChunkValues == 1 ? values[(int) valueIdxOffset] : 0L;
            for (int r = rowInChunk; r < end; r++) {
                c.accept(v);
            }
        } else {
            for (int r = rowInChunk; r < end; r++) {
                int localIdx = indices[chunkBase + r];
                if (localIdx >= numChunkValues) {
                    localIdx = numChunkValues - 1;
                }
                c.accept(values[(int) valueIdxOffset + localIdx]);
            }
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long[] acc = {identity};
        forEachLong(v -> acc[0] = op.applyAsLong(acc[0], v));
        return acc[0];
    }
}
