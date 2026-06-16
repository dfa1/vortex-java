package io.github.dfa1.vortex.reader.array;

/// Package-private constants and helpers shared by the `LazyRleXxxArray` records.
///
/// FastLanes RLE works in fixed 1024-row chunks; per-row decode is
/// `chunkIdx = absRow >> 10`, `rowInChunk = absRow & 1023`. The chunk's
/// local value count comes from `valuesIdxOffsets[chunkIdx+1] - valuesIdxOffsets[chunkIdx]`;
/// the per-row local index sits in `indices[chunkIdx * 1024 + rowInChunk]` and
/// must be clamped to `numChunkValues - 1` (the writer encodes the constant-run
/// case with a single value and may leave the slot's bits as 0).
final class RleArrays {

    /// Fixed FastLanes chunk size in rows.
    static final int FL_CHUNK_SIZE = 1024;

    /// `log2(FL_CHUNK_SIZE)` — used for cheap shift-based chunk indexing.
    static final int FL_LOG2 = 10;

    /// Mask for `absRow % FL_CHUNK_SIZE`.
    static final int FL_MASK = FL_CHUNK_SIZE - 1;

    private RleArrays() {
    }

    /// Returns the number of distinct values in `chunkIdx`.
    ///
    /// @param chunkIdx         chunk index in `[0, numChunks)`
    /// @param numChunks        total chunk count
    /// @param valuesIdxOffsets per-chunk starting offsets into the global values pool
    ///                         (length = `numChunks`)
    /// @param firstOffset      absolute origin of the pool (subtracted before lookup)
    /// @param valuesLen        total length of the values pool
    /// @return distinct value count for `chunkIdx`
    static int chunkValueCount(int chunkIdx, int numChunks, long[] valuesIdxOffsets,
            long firstOffset, long valuesLen) {
        long start = valuesIdxOffsets[chunkIdx] - firstOffset;
        long end = chunkIdx + 1 < numChunks
                ? valuesIdxOffsets[chunkIdx + 1] - firstOffset
                : valuesLen;
        return (int) (end - start);
    }
}
