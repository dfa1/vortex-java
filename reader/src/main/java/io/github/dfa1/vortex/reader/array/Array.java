package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Optional;

/// Decoded columnar data. Concrete subtypes specialize element access for the JIT;
/// each covers a specific dtype family.
///
/// Buffers are `MemorySegment` slices backed by the memory-mapped file; lifetime
/// is tied to the `VortexFile`'s Arena.
public sealed interface Array
        permits BoolArray, ByteArray, DecimalArray, DoubleArray, FixedSizeListArray,
                        Float16Array, FloatArray, GenericArray, IntArray, ListArray, ListViewArray,
                        LongArray, MapArray, MaskedArray, NullArray, ShortArray, StructArray, UnknownArray,
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

    /// Materializes this array into its primary backing [MemorySegment],
    /// allocating from `arena` for lazy variants.
    ///
    /// Segment-backed arrays (the `Materialized*` records, `VarBinArray`,
    /// `GenericArray`, `LazyDecimalArray`) return their existing buffer with no
    /// copy. Lazy primitive arrays decode element-by-element, the `Lazy*`
    /// frame-of-reference / zigzag / ALP variants apply their inlined formula in a
    /// vectorizable loop, and composite arrays (chunked, dict) concatenate or gather
    /// their children. This is the single materialization contract behind
    /// [io.github.dfa1.vortex.reader.decode.DecodeContext#materialize(Array)].
    ///
    /// Array families with no row-addressable primary segment (struct, list, variant,
    /// the byte-parts decimal layout) throw [io.github.dfa1.vortex.core.error.VortexException].
    ///
    /// @param arena allocator used to materialize lazy variants
    /// @return the primary [MemorySegment]
    MemorySegment materialize(SegmentAllocator arena);

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

    /// Returns this array's primary backing segment if it is already segment-backed,
    /// otherwise empty — a non-allocating probe.
    ///
    /// Unlike [#materialize(java.lang.foreign.SegmentAllocator)], this never allocates or
    /// decodes: lazy and composite arrays return empty rather than being materialized. The
    /// default is empty; segment-backed types (the `Materialized*` records, `VarBinArray`,
    /// `GenericArray`, `LazyDecimalArray`) override to return their existing buffer, and
    /// [MaskedArray] delegates to its inner data. The scan layer's dictionary zip-bomb guard
    /// uses it to inspect a codes buffer's real size without expanding an oversized claimed
    /// row count.
    ///
    /// **Vortex-internal.** Application code should prefer the typed accessors on concrete
    /// subtypes ([LongArray#getLong(long)], [IntArray#getInt(long)], …) or
    /// [#materialize(java.lang.foreign.SegmentAllocator)].
    ///
    /// @return the primary [MemorySegment], or empty if this array has no segment backing
    default Optional<MemorySegment> segmentIfPresent() {
        return Optional.empty();
    }
}
