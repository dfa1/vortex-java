package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

/// Decoded columnar data. Concrete subtypes specialise element access for the JIT;
/// each covers a specific dtype family.
///
/// Buffers are `MemorySegment` slices backed by the memory-mapped file; lifetime
/// is tied to the `VortexFile`'s Arena.
public sealed interface Array
        permits BoolArray, ByteArray, DecimalArray, DoubleArray, FixedSizeListArray,
                        Float16Array, FloatArray, GenericArray, IntArray, ListArray, ListViewArray,
                        LongArray, MaskedArray, NullArray, ShortArray, StructArray, UnknownArray,
                        VarBinArray, VariantArray {

    /// Returns the number of elements in this array.
    ///
    /// @return element count
    long length();

    /// Returns the logical type of elements in this array.
    ///
    /// @return dtype
    DType dtype();

    /// Returns an array holding only the first `rows` elements of this array.
    ///
    /// Low-level primitive: the caller must guarantee `0 <= rows < length()`.
    /// Prefer the [#limited(Array, long)] guard, which clamps the `rows >= length()`
    /// case to a no-op and rejects negatives — the scan layer and all composite
    /// subtypes route through it.
    ///
    /// Most subtypes implement this without copying: the primitive families
    /// ([LongArray], [IntArray], …) return a length-capping view; chunked arrays
    /// keep their whole prefix children and only re-cut the boundary child;
    /// composite arrays (struct/list/variant) limit their children. Only
    /// [UnknownArray] — raw, undecoded data with no row-addressable structure —
    /// cannot be limited and throws.
    ///
    /// @param rows number of leading elements to keep; must be in `[0, length())`
    /// @return an array of length `rows`
    Array limited(long rows);

    /// Limits `arr` to its first `rows` elements (semantically `min(length, rows)`),
    /// returning it unchanged when it already fits. Single guard shared by the scan
    /// layer and the composite subtypes that recurse into children, so the
    /// `rows >= length()` no-op and the negative-`rows` rejection live in one place.
    ///
    /// @param arr  array to limit
    /// @param rows desired maximum length; must be `>= 0`
    /// @return `arr` when `arr.length() <= rows`, otherwise `arr.limited(rows)`
    /// @throws IllegalArgumentException if `rows` is negative
    static Array limited(Array arr, long rows) {
        if (rows < 0) {
            throw new IllegalArgumentException("rows must be >= 0, got " + rows);
        }
        return arr.length() <= rows ? arr : arr.limited(rows);
    }
}
