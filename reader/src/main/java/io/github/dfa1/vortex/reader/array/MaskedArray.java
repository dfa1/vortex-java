package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Optional;

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

    /// Result of [#unwrap(Array)]: a payload and the validity bitmap it carried.
    ///
    /// @param inner    the non-nullable payload
    /// @param validity the validity bitmap, or `null` if all positions are valid
    public record Unwrapped(Array inner, BoolArray validity) {
    }

    /// Splits `a` into its non-nullable payload and validity bitmap. Many decoders receive a
    /// child whose own validity they must mirror (RunEnd, ALP, ALP-RD, ZigZag,
    /// FrameOfReference, DateTimeParts, Dict, RLE, Sparse) by decoding it as an `Array` rather
    /// than a raw segment, so a nullable child surfaces here as a `MaskedArray` instead of
    /// being silently flattened with its nulls dropped; this is the shared unwrap step every
    /// one of those decode paths needs before working with the payload directly.
    ///
    /// @param a the array to split, typically a freshly decoded child
    /// @return `a`'s payload and validity, or `(a, null)` if `a` was not a `MaskedArray`
    public static Unwrapped unwrap(Array a) {
        return a instanceof MaskedArray masked
                ? new Unwrapped(masked.child, masked.validity)
                : new Unwrapped(a, null);
    }

    /// Returns just the non-nullable payload of `a`, discarding validity. Shorthand for
    /// [#unwrap(Array)]`.inner()` at call sites that don't need the validity bit — e.g. an
    /// indices/positions child that is itself never nullable.
    ///
    /// @param a the array to unwrap
    /// @return `a`'s payload, or `a` itself if it was not a `MaskedArray`
    public static Array innerOrSelf(Array a) {
        return a instanceof MaskedArray masked ? masked.child : a;
    }

    /// Rewraps `inner` in a `MaskedArray` carrying `validity`, or returns `inner` unchanged
    /// when `validity` is `null` — the common "re-wrap the decoded result with the validity
    /// borrowed via [#unwrap(Array)]" tail of a decode() method.
    ///
    /// @param inner    the decoded result to wrap
    /// @param validity validity bitmap to carry, or `null` to skip wrapping
    /// @return `inner`, optionally wrapped in a `MaskedArray`
    public static Array wrapIfPresent(Array inner, BoolArray validity) {
        return validity != null ? new MaskedArray(inner, validity) : inner;
    }

    /// Casts `va` to `BoolArray`, failing loudly with `role` in the message when the decoded
    /// child was not a bool array — e.g. a crafted file naming a non-bool encoding for a
    /// validity bitmap. A raw `ClassCastException` here would violate ADR 0003.
    ///
    /// @param va       the decoded array expected to be a validity bitmap
    /// @param encoding the encoding requesting the check, used for error attribution
    /// @param role     short description of what `va` represents, used in the failure message
    /// @return `va` cast to `BoolArray`
    /// @throws VortexException if `va` is not a `BoolArray`
    public static BoolArray requireBoolArray(Array va, EncodingId encoding, String role) {
        if (!(va instanceof BoolArray validity)) {
            throw new VortexException(encoding, role + " decoded to unexpected type: " + va.getClass().getSimpleName());
        }
        return validity;
    }

    @Override
    public Array limited(long rows) {
        Array truncChild = Array.limited(child, rows);
        BoolArray truncValidity = validity != null ? (BoolArray) Array.limited(validity, rows) : null;
        return new MaskedArray(truncChild, truncValidity);
    }

    /// Materializes the inner (data) payload, ignoring the validity mask — the
    /// segment returned is the data buffer only. Unwraps to the inner array's own
    /// materialization; callers that need validity must read [#validity()] separately.
    ///
    /// @param arena allocator used to materialize lazy inner variants
    /// @return the inner payload's primary [MemorySegment]
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        return child.materialize(arena);
    }

    /// Probes the inner (data) payload's backing segment, ignoring the validity mask.
    ///
    /// @return the inner array's segment if segment-backed, otherwise empty
    @Override
    public Optional<MemorySegment> segmentIfPresent() {
        return child.segmentIfPresent();
    }
}
