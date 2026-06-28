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

    /// Visitor for [#walkChunks]. Invoked once per covered chunk with the
    /// chunk's local row span `[rowInChunk, end)`; the implementation runs its
    /// own typed inner loop over that span.
    @FunctionalInterface
    interface ChunkVisitor {

        /// Processes one chunk's row span.
        ///
        /// @param chunkIdx   chunk index in `[0, numChunks)`
        /// @param rowInChunk first local row to emit (inclusive)
        /// @param end        one past the last local row to emit
        void visit(int chunkIdx, int rowInChunk, int end);
    }

    /// Walks the logical range `[offset, offset + length)` chunk by chunk,
    /// calling `visitor` once per chunk with the local `[rowInChunk, end)` span.
    ///
    /// Centralizes the FastLanes chunk-boundary arithmetic shared by every
    /// `LazyRleXxxArray` record's `forEach` / `fold`. The visitor — not this
    /// method — owns the per-row loop, so the typed `values[...]` read stays a
    /// direct, monomorphic array access; this call fires once per chunk
    /// (≈ `length / 1024` times), never per row.
    ///
    /// @param length    logical row count to emit
    /// @param offset    starting absolute position
    /// @param numChunks total chunk count
    /// @param visitor   receives each chunk's local row span
    static void walkChunks(long length, int offset, int numChunks, ChunkVisitor visitor) {
        long emitted = 0;
        int absRow = offset;
        int startChunk = absRow >>> FL_LOG2;
        for (int chunkIdx = startChunk; chunkIdx < numChunks && emitted < length; chunkIdx++) {
            int rowInChunk = absRow - chunkIdx * FL_CHUNK_SIZE;
            int end = Math.min(FL_CHUNK_SIZE, rowInChunk + (int) (length - emitted));
            visitor.visit(chunkIdx, rowInChunk, end);
            int count = end - rowInChunk;
            emitted += count;
            absRow += count;
        }
    }
}
