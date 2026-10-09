package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.model.PType;

import java.lang.foreign.MemorySegment;

/// The vectorizable kernels shared by the reader and the writer: the tight loops where a SIMD
/// implementation can replace the scalar one without changing a single output byte.
///
/// [ScalarOperations] holds the plain loops, written to the hot-loop rule so C2 can auto-vectorize
/// them. It is always complete, so correctness never depends on which implementation
/// [VectorSupport#operations()] returns. Callers validate arguments and attribute errors; kernels
/// assume well-formed input and throw only [IllegalArgumentException] for a `ptype` they do not cover.
public interface SimdOperations {

    /// Widens `count` contiguous little-endian integer elements starting at element index
    /// `fromElement` into `out[0, count)`, zero-extending unsigned ptypes and sign-extending signed ones.
    ///
    /// @param src         the source segment
    /// @param fromElement starting element index (not byte offset) within `src`
    /// @param count       number of elements to widen
    /// @param ptype       the elements' physical type, an integer type
    /// @param out         destination array, at least `count` long
    /// @throws IllegalArgumentException if `ptype` is not an integer type
    void widenInto(MemorySegment src, long fromElement, int count, PType ptype, long[] out);

    /// Narrows `values` to the width of `ptype`, keeping the low bytes, and writes them
    /// little-endian into `dst` starting at byte 0. Handles the 1-, 2- and 4-byte types; the
    /// 8-byte types are a plain bulk copy that needs no kernel.
    ///
    /// @param values the wide values
    /// @param ptype  the target physical type, 1 to 4 bytes wide (floats keep their raw bits)
    /// @param dst    destination segment, at least `values.length * ptype.byteSize()` bytes
    /// @throws IllegalArgumentException if `ptype` is 8 bytes wide
    void narrowInto(long[] values, PType ptype, MemorySegment dst);

    /// Returns the largest of `count` contiguous little-endian unsigned elements of `src`, zero when
    /// `count` is zero: the dictionary code bound check (Rust's `vmaxq_u8` small-table guard).
    ///
    /// @param src   the source segment, at least `count * ptype.byteSize()` bytes
    /// @param count number of elements to scan
    /// @param ptype the elements' physical type: `U8`, `U16` or `U32`
    /// @return the maximum, zero-extended
    /// @throws IllegalArgumentException if `ptype` is not `U8`, `U16` or `U32`
    long maxUnsigned(MemorySegment src, long count, PType ptype);

    /// Reverses the FastLanes delta transform of one 1024-element chunk. Each of the `lanes`
    /// independent lanes is a prefix sum over `typeBits` rows, wrapping at the element width.
    /// Element `(row, lane)` sits at index `FastLanes.iterateIndex(row, lane)` in both `deltas` and `out`.
    ///
    /// @param deltas   the chunk's deltas, widened to `long`, in transposed order
    /// @param bases    the per-lane starting values, at least `lanes` long
    /// @param lanes    lane count, `1024 / typeBits`
    /// @param typeBits element width in bits (8, 16, 32 or 64)
    /// @param mask     low `typeBits` bits set
    /// @param out      destination, 1024 long, same order as `deltas`
    void undeltaChunk(long[] deltas, long[] bases, int lanes, int typeBits, long mask, long[] out);

    /// Applies the FastLanes delta transform to one 1024-element chunk: the inverse of
    /// [#undeltaChunk(long[], long[], int, int, long, long[])]. Each row's delta is its value minus
    /// the previous row's value in the same lane (the lane's base for row 0), wrapping at the element width.
    ///
    /// @param values   the chunk's values, widened to `long`, in transposed order
    /// @param bases    the per-lane starting values, at least `lanes` long
    /// @param lanes    lane count, `1024 / typeBits`
    /// @param typeBits element width in bits (8, 16, 32 or 64)
    /// @param mask     low `typeBits` bits set
    /// @param out      destination, 1024 long, same order as `values`
    void deltaChunk(long[] values, long[] bases, int lanes, int typeBits, long mask, long[] out);

    /// Bit-packs one full 1024-element FastLanes block. Each lane's `typeBits` rows are packed
    /// bit-contiguously, `bitWidth` bits per value, into `bitWidth` words of `typeBits` bits; a
    /// value straddling a word boundary carries its high bits into the next word. Word `w` of lane
    /// `l` is stored at `words[w * lanes + l]`. Values are masked to `bitWidth` bits first, so
    /// out-of-range entries (handled separately as patches) never spill into a neighbor.
    ///
    /// @param values   source array; the block is `values[offset, offset + 1024)` in logical order
    /// @param offset   index of the block's first element
    /// @param bitWidth bits per packed value, in `[1, typeBits]`
    /// @param typeBits element width in bits (8, 16, 32 or 64)
    /// @param words    destination, at least `bitWidth * (1024 / typeBits)` long, each word masked to `typeBits`
    void packBlock(long[] values, int offset, int bitWidth, int typeBits, long[] words);
}
