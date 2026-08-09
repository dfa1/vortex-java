package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
/// `values` and `indices` are read straight through their mmapped segments — the
/// record never copies the compressed payload onto the heap.
///
/// `forEachLong` / `fold` iterate chunk-by-chunk so the constant-run
/// fast path (`numChunkValues <= 1`) emits each value once with a tight inner loop.
///
/// @param dtype             logical element type
/// @param length            total logical row count
/// @param values            concatenated distinct values per chunk, as a little-endian
///                          `i64` segment of exactly `valuesLen` elements
/// @param indices           per-row local index table, as a `u8`/`u16` segment of `indices_len`
///                          elements (at least `numChunks * 1024`)
/// @param wideIndices       `true` when `indices` holds `u16` elements, `false` for `u8`
/// @param valuesIdxOffsets  per-chunk values-pool start offsets; length `numChunks`
/// @param firstOffset       absolute origin of the values pool
/// @param valuesLen         total values pool length
/// @param numChunks         number of FastLanes chunks covered
/// @param offset            starting absolute position; logical row `i` maps to
///                          absolute `i + offset`
@SuppressWarnings("java:S6218") // internal data carrier; the long[] offsets component is never compared.
public record LazyRleLongArray(
        DType dtype, long length, MemorySegment values, MemorySegment indices, boolean wideIndices,
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
            return numChunkValues == 1 ? values.getAtIndex(VortexFormat.LE_LONG, valueIdxOffset) : 0L;
        }
        int localIdx = RleArrays.localIndex(indices, wideIndices,
                (long) chunkIdx * RleArrays.FL_CHUNK_SIZE + rowInChunk);
        return values.getAtIndex(VortexFormat.LE_LONG,
                valueIdxOffset + Math.min(localIdx, numChunkValues - 1));
    }

    @Override
    public void forEachLong(LongConsumer c) {
        RleArrays.walkChunks(length, offset, numChunks,
                (chunkIdx, rowInChunk, end) -> processChunk(chunkIdx, rowInChunk, end, c));
    }

    /// Emits one chunk's rows. The `u8`/`u16` index width is hoisted into its own loop
    /// (rather than tested per row) so each body stays uniform — see the CLAUDE.md hot-loop rule.
    private void processChunk(int chunkIdx, int rowInChunk, int end, LongConsumer c) {
        long chunkBase = (long) chunkIdx * RleArrays.FL_CHUNK_SIZE;
        long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
        int numChunkValues = RleArrays.chunkValueCount(chunkIdx, numChunks, valuesIdxOffsets, firstOffset, valuesLen);
        int maxIdx = numChunkValues - 1;
        if (numChunkValues <= 1) {
            long v = numChunkValues == 1 ? values.getAtIndex(VortexFormat.LE_LONG, valueIdxOffset) : 0L;
            for (int r = rowInChunk; r < end; r++) {
                c.accept(v);
            }
        } else if (wideIndices) {
            for (int r = rowInChunk; r < end; r++) {
                int localIdx = Short.toUnsignedInt(indices.getAtIndex(VortexFormat.LE_SHORT, chunkBase + r));
                c.accept(values.getAtIndex(VortexFormat.LE_LONG, valueIdxOffset + Math.min(localIdx, maxIdx)));
            }
        } else {
            for (int r = rowInChunk; r < end; r++) {
                int localIdx = Byte.toUnsignedInt(indices.get(ValueLayout.JAVA_BYTE, chunkBase + r));
                c.accept(values.getAtIndex(VortexFormat.LE_LONG, valueIdxOffset + Math.min(localIdx, maxIdx)));
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
