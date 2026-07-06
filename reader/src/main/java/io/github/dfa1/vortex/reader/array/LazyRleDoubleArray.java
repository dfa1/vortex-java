package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Lazy FastLanes-RLE-encoded [DoubleArray]. See [LazyRleLongArray] for semantics.
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
public record LazyRleDoubleArray(
        DType dtype, long length, double[] values, int[] indices,
        long[] valuesIdxOffsets, long firstOffset, long valuesLen,
        int numChunks, int offset)
        implements DoubleArray {

    @Override
    public double getDouble(long i) {
        int absRow = (int) (i + offset);
        int chunkIdx = absRow >>> RleArrays.FL_LOG2;
        int rowInChunk = absRow & RleArrays.FL_MASK;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        if (numChunkValues <= 1) {
            return numChunkValues == 1 ? values[(int) valueIdxOffset] : 0.0;
        }
        int localIdx = indices[chunkIdx * RleArrays.FL_CHUNK_SIZE + rowInChunk];
        if (localIdx >= numChunkValues) {
            localIdx = numChunkValues - 1;
        }
        return values[(int) valueIdxOffset + localIdx];
    }

    @Override
    public void forEachDouble(DoubleConsumer c) {
        RleArrays.walkChunks(length, offset, numChunks,
                (chunkIdx, rowInChunk, end) -> processChunk(chunkIdx, rowInChunk, end, c));
    }

    private void processChunk(int chunkIdx, int rowInChunk, int end, DoubleConsumer c) {
        int chunkBase = chunkIdx * RleArrays.FL_CHUNK_SIZE;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        if (numChunkValues <= 1) {
            double v = numChunkValues == 1 ? values[(int) valueIdxOffset] : 0.0;
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
    public double fold(double identity, DoubleBinaryOperator op) {
        double[] acc = {identity};
        forEachDouble(v -> acc[0] = op.applyAsDouble(acc[0], v));
        return acc[0];
    }
}
