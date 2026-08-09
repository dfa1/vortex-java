package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Lazy FastLanes-RLE-encoded [DoubleArray]. See [LazyRleLongArray] for semantics.
///
/// @param dtype             logical element type
/// @param length            total logical row count
/// @param values            concatenated distinct values per chunk, as a little-endian
///                          `f64` segment of exactly `valuesLen` elements
/// @param indices           per-row local index table, as a `u8`/`u16` segment
/// @param wideIndices       `true` when `indices` holds `u16` elements, `false` for `u8`
/// @param valuesIdxOffsets  per-chunk values-pool start offsets
/// @param firstOffset       absolute origin of the values pool
/// @param valuesLen         total values pool length
/// @param numChunks         number of FastLanes chunks covered
/// @param offset            starting absolute position
@SuppressWarnings("java:S6218") // internal data carrier; the long[] offsets component is never compared.
public record LazyRleDoubleArray(
        DType dtype, long length, MemorySegment values, MemorySegment indices, boolean wideIndices,
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
            return numChunkValues == 1 ? values.getAtIndex(VortexFormat.LE_DOUBLE, valueIdxOffset) : 0.0;
        }
        int localIdx = RleArrays.localIndex(indices, wideIndices,
                (long) chunkIdx * RleArrays.FL_CHUNK_SIZE + rowInChunk);
        return values.getAtIndex(VortexFormat.LE_DOUBLE,
                valueIdxOffset + Math.min(localIdx, numChunkValues - 1));
    }

    @Override
    public void forEachDouble(DoubleConsumer c) {
        RleArrays.walkChunks(length, offset, numChunks,
                (chunkIdx, rowInChunk, end) -> processChunk(chunkIdx, rowInChunk, end, c));
    }

    /// Emits one chunk's rows. The `u8`/`u16` index width is hoisted into its own loop
    /// (rather than tested per row) so each body stays uniform — see the CLAUDE.md hot-loop rule.
    private void processChunk(int chunkIdx, int rowInChunk, int end, DoubleConsumer c) {
        long chunkBase = (long) chunkIdx * RleArrays.FL_CHUNK_SIZE;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        int maxIdx = numChunkValues - 1;
        if (numChunkValues <= 1) {
            double v = numChunkValues == 1 ? values.getAtIndex(VortexFormat.LE_DOUBLE, valueIdxOffset) : 0.0;
            for (int r = rowInChunk; r < end; r++) {
                c.accept(v);
            }
        } else if (wideIndices) {
            for (int r = rowInChunk; r < end; r++) {
                int localIdx = Short.toUnsignedInt(indices.getAtIndex(VortexFormat.LE_SHORT, chunkBase + r));
                c.accept(values.getAtIndex(VortexFormat.LE_DOUBLE, valueIdxOffset + Math.min(localIdx, maxIdx)));
            }
        } else {
            for (int r = rowInChunk; r < end; r++) {
                int localIdx = Byte.toUnsignedInt(indices.get(ValueLayout.JAVA_BYTE, chunkBase + r));
                c.accept(values.getAtIndex(VortexFormat.LE_DOUBLE, valueIdxOffset + Math.min(localIdx, maxIdx)));
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
