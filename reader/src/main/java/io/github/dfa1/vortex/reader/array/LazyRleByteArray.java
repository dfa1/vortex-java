package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.LongBinaryOperator;

/// Lazy FastLanes-RLE-encoded {@link ByteArray}. See {@link LazyRleLongArray} for semantics.
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
/// @param unsigned          {@code true} when the dtype is U8 (affects {@link #getInt(long)} widening)
public record LazyRleByteArray(
        DType dtype, long length, byte[] values, int[] indices,
        long[] valuesIdxOffsets, long firstOffset, long valuesLen,
        int numChunks, int offset, boolean unsigned)
        implements ByteArray {

    private byte lookup(long i) {
        int absRow = (int) (i + offset);
        int chunkIdx = absRow >>> RleArrays.FL_LOG2;
        int rowInChunk = absRow & RleArrays.FL_MASK;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        if (numChunkValues <= 1) {
            return numChunkValues == 1 ? values[(int) valueIdxOffset] : (byte) 0;
        }
        int localIdx = indices[chunkIdx * RleArrays.FL_CHUNK_SIZE + rowInChunk];
        if (localIdx >= numChunkValues) {
            localIdx = numChunkValues - 1;
        }
        return values[(int) valueIdxOffset + localIdx];
    }

    @Override
    public byte getByte(long i) {
        return lookup(i);
    }

    @Override
    public int getInt(long i) {
        byte v = lookup(i);
        return unsigned ? Byte.toUnsignedInt(v) : v;
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long acc = identity;
        long n = length;
        long emitted = 0;
        int absRow = offset;
        int startChunk = absRow >>> RleArrays.FL_LOG2;
        for (int chunkIdx = startChunk; chunkIdx < numChunks && emitted < n; chunkIdx++) {
            int chunkBase = chunkIdx * RleArrays.FL_CHUNK_SIZE;
            int rowInChunk = absRow - chunkBase;
            long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
            int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
            int end = Math.min(RleArrays.FL_CHUNK_SIZE, rowInChunk + (int) (n - emitted));
            if (numChunkValues <= 1) {
                byte v = numChunkValues == 1 ? values[(int) valueIdxOffset] : (byte) 0;
                long widened = unsigned ? Byte.toUnsignedInt(v) : v;
                for (int r = rowInChunk; r < end; r++) {
                    acc = op.applyAsLong(acc, widened);
                }
            } else {
                for (int r = rowInChunk; r < end; r++) {
                    int localIdx = indices[chunkBase + r];
                    if (localIdx >= numChunkValues) {
                        localIdx = numChunkValues - 1;
                    }
                    byte v = values[(int) valueIdxOffset + localIdx];
                    long widened = unsigned ? Byte.toUnsignedInt(v) : v;
                    acc = op.applyAsLong(acc, widened);
                }
            }
            int count = end - rowInChunk;
            emitted += count;
            absRow += count;
        }
        return acc;
    }
}
