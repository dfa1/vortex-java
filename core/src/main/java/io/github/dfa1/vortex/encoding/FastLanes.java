package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.PType;

/// Shared FastLanes layout constants and index math used by the bit-packing and delta encodings on
/// both the read and write sides.
///
/// FastLanes processes values in fixed 1024-element chunks ([#CHUNK]) arranged into an interleaved
/// lane order ([#ORDER]) so that the unpack inner loop is data-parallel. [#transposeIndex(int)] and
/// [#iterateIndex(int, int)] map between the logical element order and that interleaved layout;
/// [#lanes(PType)] is the lane count for a width and [#lowMask(int)] the low-`bits` value mask.
///
/// Mirrors the reference layout in `spiraldb/fastlanes` (`src/macros.rs`).
public final class FastLanes {

    /// Number of elements per FastLanes chunk.
    public static final int CHUNK = 1024;

    /// The FastLanes transpose order — the lane permutation applied within each 8-row group.
    private static final int[] ORDER = {0, 4, 2, 6, 1, 5, 3, 7};

    /// Precomputed logical-to-transposed permutation for one chunk (see [#transposeIndex(int)]).
    private static final int[] TRANSPOSE = new int[CHUNK];

    /// Precomputed per-row base offsets for [#iterateIndex(int, int)]; the lane is added at use.
    /// Sized to the maximum row count (a 64-bit type has 64 rows per chunk).
    private static final int[] ITERATE_BASE = new int[64];

    static {
        for (int idx = 0; idx < CHUNK; idx++) {
            int lane = idx % 16;
            int order = (idx / 16) % 8;
            int row = idx / 128;
            TRANSPOSE[idx] = lane * 64 + ORDER[order] * 8 + row;
        }
        for (int row = 0; row < ITERATE_BASE.length; row++) {
            ITERATE_BASE[row] = ORDER[row / 8] * 16 + (row % 8) * 128;
        }
    }

    private FastLanes() {
    }

    /// Maps a logical element index to its position in the transposed (interleaved-lane) layout.
    ///
    /// The mapping is precomputed into a per-chunk table; the lookup avoids the per-element
    /// division and `ORDER` indirection that would otherwise serialize address generation in the
    /// transpose hot loop.
    ///
    /// @param idx logical element index within a chunk, in `[0, CHUNK)`
    /// @return the corresponding index in the transposed buffer
    public static int transposeIndex(int idx) {
        return TRANSPOSE[idx];
    }

    /// Computes the logical element index visited at the given `row` and `lane` of the FastLanes
    /// iteration order — the inverse mapping used while packing or unpacking.
    ///
    /// The row-dependent part is precomputed; only the lane is added per call, keeping the
    /// pack/unpack inner loop free of division and `ORDER` indirection.
    ///
    /// @param row  the row within the chunk
    /// @param lane the lane within the row
    /// @return the logical element index
    public static int iterateIndex(int row, int lane) {
        return ITERATE_BASE[row] + lane;
    }

    /// Returns the FastLanes lane count for `ptype` — [#CHUNK] divided by the type's bit width.
    ///
    /// @param ptype the physical type being packed
    /// @return the number of lanes
    public static int lanes(PType ptype) {
        return CHUNK / ptype.bits();
    }

    /// Returns a mask selecting the low `bits` of a `long` (all ones when `bits == 64`).
    ///
    /// @param bits the number of low bits to keep, in `[1, 64]`
    /// @return the low-`bits` mask
    public static long lowMask(int bits) {
        return bits == 64 ? -1L : (1L << bits) - 1;
    }
}
