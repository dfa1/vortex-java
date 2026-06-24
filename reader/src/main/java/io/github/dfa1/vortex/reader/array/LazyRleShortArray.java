package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.function.LongBinaryOperator;

/// Lazy FastLanes-RLE-encoded [ShortArray]. See [LazyRleLongArray] for semantics.
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
/// @param unsigned          `true` when the dtype is U16 (affects [#getInt(long)] widening)
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record LazyRleShortArray(
        DType dtype, long length, short[] values, int[] indices,
        long[] valuesIdxOffsets, long firstOffset, long valuesLen,
        int numChunks, int offset, boolean unsigned)
        implements ShortArray {

    private short lookup(long i) {
        int absRow = (int) (i + offset);
        int chunkIdx = absRow >>> RleArrays.FL_LOG2;
        int rowInChunk = absRow & RleArrays.FL_MASK;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        if (numChunkValues <= 1) {
            return numChunkValues == 1 ? values[(int) valueIdxOffset] : (short) 0;
        }
        int localIdx = indices[chunkIdx * RleArrays.FL_CHUNK_SIZE + rowInChunk];
        if (localIdx >= numChunkValues) {
            localIdx = numChunkValues - 1;
        }
        return values[(int) valueIdxOffset + localIdx];
    }

    @Override
    public short getShort(long i) {
        return lookup(i);
    }

    @Override
    public int getInt(long i) {
        short v = lookup(i);
        return unsigned ? Short.toUnsignedInt(v) : v;
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long[] acc = {identity};
        RleArrays.walkChunks(length, offset, numChunks,
                (chunkIdx, rowInChunk, end) -> acc[0] = foldChunk(chunkIdx, rowInChunk, end, acc[0], op));
        return acc[0];
    }

    private long widen(short v) {
        return unsigned ? Short.toUnsignedInt(v) : v;
    }

    private long foldChunk(int chunkIdx, int rowInChunk, int end, long acc, LongBinaryOperator op) {
        int chunkBase = chunkIdx * RleArrays.FL_CHUNK_SIZE;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        if (numChunkValues <= 1) {
            short v = numChunkValues == 1 ? values[(int) valueIdxOffset] : (short) 0;
            long widened = widen(v);
            for (int r = rowInChunk; r < end; r++) {
                acc = op.applyAsLong(acc, widened);
            }
        } else {
            for (int r = rowInChunk; r < end; r++) {
                int localIdx = indices[chunkBase + r];
                if (localIdx >= numChunkValues) {
                    localIdx = numChunkValues - 1;
                }
                acc = op.applyAsLong(acc, widen(values[(int) valueIdxOffset + localIdx]));
            }
        }
        return acc;
    }
}
