package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Decoded `vortex.masked` array: a non-nullable child paired with an optional validity bitmap.
///
/// Invariant: `child` has no actual nulls — nullability is expressed solely via
/// `validity`. When `validity` is `null` all positions are valid.
///
/// Use [#inner()] to access the payload and [#isValid(long)] to check validity
/// before trusting a value.
public final class MaskedArray implements Array {

    private final Array child;
    private final BoolArray validity;

    /// Creates a new `MaskedArray` wrapping a non-nullable child with an optional validity bitmap.
    ///
    /// @param child    non-nullable payload array
    /// @param validity validity bitmap, or `null` if all positions are valid
    public MaskedArray(Array child, BoolArray validity) {
        this.child = child;
        this.validity = validity;
    }

    @Override
    public DType dtype() {
        return child.dtype().withNullable(true);
    }

    @Override
    public long length() {
        return child.length();
    }

    /// Returns the non-nullable payload array wrapped by this masked array.
    ///
    /// @return the inner payload [Array]
    public Array inner() {
        return child;
    }

    /// Returns the validity bitmap, or `null` if all positions are valid.
    ///
    /// @return the validity bitmap, or `null` when all values are valid
    public BoolArray validity() {
        return validity;
    }

    /// Returns `true` if the position at index `i` is valid (not null).
    ///
    /// @param i zero-based logical index
    /// @return `true` if the value at `i` is valid
    public boolean isValid(long i) {
        return validity == null || validity.getBoolean(i);
    }

    @Override
    public Array limited(long rows) {
        Array truncChild = Array.limited(child, rows);
        BoolArray truncValidity = validity != null ? (BoolArray) Array.limited(validity, rows) : null;
        return new MaskedArray(truncChild, truncValidity);
    }

    /// Materialises the inner (data) payload, ignoring the validity mask — the
    /// segment returned is the data buffer only. This matches the prior
    /// `ArraySegments` behaviour of unwrapping a masked array to its inner data;
    /// callers that need validity must read [#validity()] separately.
    ///
    /// @param arena allocator used to materialise lazy inner variants
    /// @return the inner payload's primary [MemorySegment]
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        return child.materialize(arena);
    }
}
