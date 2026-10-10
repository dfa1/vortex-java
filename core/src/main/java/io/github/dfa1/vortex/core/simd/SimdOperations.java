package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.model.PType;

import java.lang.foreign.MemorySegment;
import java.util.OptionalLong;

/// The vectorizable kernels shared by the reader and the writer: the tight loops where a SIMD
/// implementation can replace the auto-vectorized one without changing a single output byte.
///
/// [AutoVectorizedSimdOperations] holds the plain loops, written to the hot-loop rule so C2 can auto-vectorize
/// them. It is always complete, so correctness never depends on which implementation
/// [SimdOperationsSupport#preferred()] returns. Callers validate arguments and attribute errors; kernels
/// assume well-formed input and throw only [IllegalArgumentException] for a `ptype` they do not cover.
public interface SimdOperations {

    /// Widens `count` contiguous little-endian elements starting at element index `fromElement`
    /// into `out[0, count)`: signed integers are sign-extended, unsigned integers zero-extended, and
    /// floating-point values contribute their raw bits, zero-extended.
    ///
    /// @param src         the source segment
    /// @param fromElement starting element index (not byte offset) within `src`
    /// @param count       number of elements to widen
    /// @param ptype       the elements' physical type
    /// @param out         destination array, at least `count` long
    void widenInto(MemorySegment src, long fromElement, int count, PType ptype, long[] out);

    /// The heap-array counterpart of [#widenInto]: widens `count` elements of a primitive array
    /// starting at index `from` into `out[0, count)`, with the same extension rules.
    ///
    /// @param values an array matching `ptype`'s carrier (`short[]` for `F16`)
    /// @param from   starting element index within `values`
    /// @param count  number of elements to widen
    /// @param ptype  the elements' physical type
    /// @param out    destination array, at least `count` long
    void widenArrayInto(Object values, int from, int count, PType ptype, long[] out);

    /// Narrows `values` to the width of `ptype`, keeping the low bytes, and writes them
    /// little-endian into `dst` starting at byte 0. Floating-point types keep their raw bits, and the
    /// 8-byte types are written as they are.
    ///
    /// @param values the wide values
    /// @param ptype  the target physical type
    /// @param dst    destination segment, at least `values.length * ptype.byteSize()` bytes
    void narrowInto(long[] values, PType ptype, MemorySegment dst);

    /// The heap-array counterpart of [#narrowInto]: narrows `values` to the width of `ptype`, keeping
    /// the low bytes, into the primitive array `out`. Floating-point carriers are rebuilt from the raw
    /// bits, the inverse of [#widenArrayInto].
    ///
    /// @param values the wide values
    /// @param ptype  the target physical type
    /// @param out    an array matching `ptype`'s carrier (`short[]` for `F16`), at least `values.length` long
    void narrowArrayInto(long[] values, PType ptype, Object out);

    /// Returns the largest of `count` contiguous little-endian unsigned elements of `src`, zero when
    /// `count` is zero: the dictionary code bound check (Rust's `vmaxq_u8` small-table guard).
    ///
    /// @param src   the source segment, at least `count * ptype.byteSize()` bytes
    /// @param count number of elements to scan
    /// @param ptype the elements' physical type: `U8`, `U16`, `U32` or `U64`
    /// @return the maximum, zero-extended (as its bit pattern for `U64`)
    /// @throws IllegalArgumentException if `ptype` is not an unsigned integer type
    long maxUnsigned(MemorySegment src, long count, PType ptype);

    /// Returns the smallest and largest element in the ptype's natural order, widened as
    /// [#widenArrayInto] does (a float contributes its raw bits). Integers compare as their type
    /// does, `U64` unsigned. Floating-point values skip `NaN`, as Rust's `min`/`max` do, and `-0.0`
    /// equals `0.0`: among equal zeros the first one in array order is the result.
    ///
    /// @param values an array matching `ptype`'s carrier (`short[]` for `F16`)
    /// @param ptype  the elements' physical type
    /// @return `{min, max}`; for a floating-point array with no non-`NaN` element (including an
    ///         empty one) an empty array
    /// @throws IllegalArgumentException if `values` is empty and `ptype` is an integer type
    long[] minMax(Object values, PType ptype);

    /// Returns whether every element of `values` equals the first, comparing floating-point values by
    /// their raw bits: distinct NaN payloads, and `-0.0` against `0.0`, are not equal. An empty array
    /// is trivially equal.
    ///
    /// @param values an array matching `ptype`'s carrier (`short[]` for `F16`)
    /// @param ptype  the elements' physical type
    /// @return `true` if all elements are equal
    boolean allEqual(Object values, PType ptype);

    /// Returns whether every element of `values` equals the first; an empty array is trivially equal.
    ///
    /// @param values the flags
    /// @return `true` if all elements are equal
    boolean allEqual(boolean[] values);

    /// Sums an integer array in Rust's widened shape: signed types as a signed `long`, unsigned types
    /// as an unsigned one (returned as its bit pattern). Overflow is checked per addition, in order,
    /// so a partial sum that overflows reports overflow even if later terms would bring the total
    /// back in range (Rust's `checked_add`). Only `I64` and `U64` can overflow: the narrower widths
    /// cannot, for any array that fits in memory.
    ///
    /// @param values an array matching `ptype`'s carrier; empty sums to zero
    /// @param ptype  the elements' physical type, an integer type
    /// @return the sum, or empty on overflow
    /// @throws IllegalArgumentException if `ptype` is not an integer type
    OptionalLong sum(Object values, PType ptype);

    /// Sums a floating-point array into a `double`, strictly left to right. The order is part of the
    /// contract: reassociating a float sum changes its rounding, and with it the stats bits we write.
    ///
    /// @param values a `short[]` (`F16`), `float[]` (`F32`) or `double[]` (`F64`)
    /// @param ptype  the elements' physical type, a floating-point type
    /// @return the sum, zero for an empty array
    /// @throws IllegalArgumentException if `ptype` is not a floating-point type
    double sumFloating(Object values, PType ptype);

    /// Counts the runs of equal neighbors: one plus every position whose element differs from its
    /// predecessor, and zero for an empty array. Floating-point elements are compared by value, as
    /// `!=`: a `NaN` always differs from its neighbor, and `0.0` equals `-0.0`. That is unlike
    /// [#allEqual(Object, PType)], which compares raw bits.
    ///
    /// @param values an array matching `ptype`'s carrier (`short[]` for `F16`, compared as raw bits)
    /// @param ptype  the elements' physical type
    /// @return the number of runs
    long runs(Object values, PType ptype);

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
