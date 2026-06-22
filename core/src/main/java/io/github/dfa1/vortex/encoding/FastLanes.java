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

    private FastLanes() {
    }

    /// Maps a logical element index to its position in the transposed (interleaved-lane) layout.
    ///
    /// @param idx logical element index within a chunk, in `[0, CHUNK)`
    /// @return the corresponding index in the transposed buffer
    public static int transposeIndex(int idx) {
        int lane = idx % 16;
        int order = (idx / 16) % 8;
        int row = idx / 128;
        return lane * 64 + ORDER[order] * 8 + row;
    }

    /// Computes the logical element index visited at the given `row` and `lane` of the FastLanes
    /// iteration order — the inverse mapping used while packing or unpacking.
    ///
    /// @param row  the row within the chunk
    /// @param lane the lane within the row
    /// @return the logical element index
    public static int iterateIndex(int row, int lane) {
        int o = row / 8;
        int s = row % 8;
        return ORDER[o] * 16 + s * 128 + lane;
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
