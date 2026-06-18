package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

/// Decoded columnar data. Concrete subtypes specialise element access for the JIT;
/// each covers a specific dtype family.
///
/// Buffers are `MemorySegment` slices backed by the memory-mapped file; lifetime
/// is tied to the `VortexFile`'s Arena.
public sealed interface Array
        permits BoolArray, ByteArray, DecimalArray, DoubleArray, EmptyArray, FixedSizeListArray,
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
    /// Used by the scan layer to honour [io.github.dfa1.vortex.reader.ScanOptions]
    /// row limits. Callers must guarantee `rows < length()` — use the
    /// [#truncated(Array, long)] guard, which short-circuits the no-op case.
    ///
    /// The primitive family interfaces ([LongArray], [IntArray], …) provide a
    /// zero-copy default: a length-capping view over `this` (no buffer is copied).
    /// Subtypes that can do better override it — chunked arrays keep their whole
    /// prefix children and only truncate the boundary child, preserving batch
    /// iteration. The base default fails fast for types with no truncation support
    /// (struct, list, …).
    ///
    /// @param rows number of leading elements to keep; must be `< length()`
    /// @return a new array of length `rows`
    /// @throws VortexException if truncation is not supported for this array type
    default Array truncate(long rows) {
        throw new VortexException("limit: truncation not supported for " + getClass().getSimpleName());
    }

    /// Truncates `arr` to `rows` elements, returning it unchanged when it already
    /// fits. Single guard shared by the scan layer and the composite subtypes that
    /// recurse into children, so the `rows >= length()` no-op is handled in exactly
    /// one place.
    ///
    /// @param arr  array to truncate
    /// @param rows desired length
    /// @return `arr` when `arr.length() <= rows`, otherwise `arr.truncate(rows)`
    static Array truncated(Array arr, long rows) {
        return arr.length() <= rows ? arr : arr.truncate(rows);
    }
}
