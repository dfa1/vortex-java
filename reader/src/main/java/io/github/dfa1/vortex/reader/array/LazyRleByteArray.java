package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.function.LongBinaryOperator;

/// Lazy FastLanes-RLE-encoded [ByteArray]. See [LazyRleLongArray] for semantics.
///
/// @param dtype             logical element type
/// @param length            total logical row count
/// @param values            concatenated distinct values per chunk, as an `i8` segment
///                          of exactly `valuesLen` elements
/// @param indices           per-row local index table, as a `u8`/`u16` segment
/// @param wideIndices       `true` when `indices` holds `u16` elements, `false` for `u8`
/// @param valuesIdxOffsets  per-chunk values-pool start offsets
/// @param firstOffset       absolute origin of the values pool
/// @param valuesLen         total values pool length
/// @param numChunks         number of FastLanes chunks covered
/// @param offset            starting absolute position
/// @param unsigned          `true` when the dtype is U8 (affects [#getInt(long)] widening)
@SuppressWarnings("java:S6218") // internal data carrier; the long[] offsets component is never compared.
public record LazyRleByteArray(
        DType dtype, long length, MemorySegment values, MemorySegment indices, boolean wideIndices,
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
            return numChunkValues == 1 ? values.get(ValueLayout.JAVA_BYTE, valueIdxOffset) : (byte) 0;
        }
        int localIdx = RleArrays.localIndex(indices, wideIndices,
                (long) chunkIdx * RleArrays.FL_CHUNK_SIZE + rowInChunk);
        return values.get(ValueLayout.JAVA_BYTE,
                valueIdxOffset + Math.min(localIdx, numChunkValues - 1));
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
        long[] acc = {identity};
        RleArrays.walkChunks(length, offset, numChunks,
                (chunkIdx, rowInChunk, end) -> acc[0] = foldChunk(chunkIdx, rowInChunk, end, acc[0], op));
        return acc[0];
    }

    private long widen(byte v) {
        return unsigned ? Byte.toUnsignedInt(v) : v;
    }

    /// Folds one chunk's rows. The `u8`/`u16` index width is hoisted into its own loop
    /// (rather than tested per row) so each body stays uniform — see the CLAUDE.md hot-loop rule.
    private long foldChunk(int chunkIdx, int rowInChunk, int end, long acc, LongBinaryOperator op) {
        long chunkBase = (long) chunkIdx * RleArrays.FL_CHUNK_SIZE;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        int maxIdx = numChunkValues - 1;
        if (numChunkValues <= 1) {
            byte v = numChunkValues == 1 ? values.get(ValueLayout.JAVA_BYTE, valueIdxOffset) : (byte) 0;
            long widened = widen(v);
            for (int r = rowInChunk; r < end; r++) {
                acc = op.applyAsLong(acc, widened);
            }
        } else if (wideIndices) {
            for (int r = rowInChunk; r < end; r++) {
                int localIdx = Short.toUnsignedInt(indices.getAtIndex(VortexFormat.LE_SHORT, chunkBase + r));
                acc = op.applyAsLong(acc, widen(values.get(ValueLayout.JAVA_BYTE,
                        valueIdxOffset + Math.min(localIdx, maxIdx))));
            }
        } else {
            for (int r = rowInChunk; r < end; r++) {
                int localIdx = Byte.toUnsignedInt(indices.get(ValueLayout.JAVA_BYTE, chunkBase + r));
                acc = op.applyAsLong(acc, widen(values.get(ValueLayout.JAVA_BYTE,
                        valueIdxOffset + Math.min(localIdx, maxIdx))));
            }
        }
        return acc;
    }
}
