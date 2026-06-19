package io.github.dfa1.vortex.reader.array;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

/// Internal non-allocating probe for an [Array]'s primary [MemorySegment].
///
/// Unwraps a [MaskedArray] to its inner (data) array first; the validity mask is
/// not surfaced here — callers that need validity must read it from the
/// [MaskedArray] separately. To force a segment (materialising lazy variants),
/// call [Array#materialize(java.lang.foreign.SegmentAllocator)] directly.
///
/// **Vortex-internal — not public API.** This class is `public` only because the reader,
/// writer, and encoding implementations live in separate Maven modules and need cross-package
/// access; its signatures may change without a deprecation cycle. It backs the scan layer's
/// dictionary zip-bomb validation, which needs to inspect a backing buffer only when one
/// already exists. Application code should prefer the typed accessors on concrete subtypes —
/// [LongArray#getLong(long)], [IntArray#getInt(long)],
/// [DoubleArray#getDouble(long)], and friends.
///
/// @deprecated transitional — this class survives only as the home of
///             [#trySegment(Array)], the non-allocating probe behind the dictionary
///             zip-bomb guard in [io.github.dfa1.vortex.reader.ScanIterator]. Once the
///             decode-limits layer owns that bound, this class is removed; do not add
///             new callers. Use [Array#materialize(java.lang.foreign.SegmentAllocator)]
///             to obtain a column's segment.
@Deprecated(forRemoval = true)
public final class ArraySegments {

    private ArraySegments() {
    }

    /// Returns the primary backing segment of `arr` if it is segment-backed, otherwise empty.
    ///
    /// Non-throwing probe for callers that want to operate on the raw buffer only when one
    /// exists (e.g. zone-map / capacity validation) and skip lazy variants without allocating.
    /// To force a segment for a lazy array, use [Array#materialize(java.lang.foreign.SegmentAllocator)].
    ///
    /// @param arr the array whose segment is needed
    /// @return the primary [MemorySegment], or empty if `arr` has no segment backing
    public static Optional<MemorySegment> trySegment(Array arr) {
        Array data = arr instanceof MaskedArray m ? m.inner() : arr;
        return switch (data) {
            case MaterializedIntArray a -> Optional.of(a.buffer());
            case MaterializedLongArray a -> Optional.of(a.buffer());
            case MaterializedDoubleArray a -> Optional.of(a.buffer());
            case MaterializedFloatArray a -> Optional.of(a.buffer());
            case MaterializedShortArray a -> Optional.of(a.buffer());
            case MaterializedByteArray a -> Optional.of(a.buffer());
            case MaterializedBoolArray a -> Optional.of(a.buffer());
            case MaterializedFloat16Array a -> Optional.of(a.buffer());
            case VarBinArray a -> Optional.of(a.bytesSegment());
            case GenericArray a -> Optional.of(a.buffer(0));
            case LazyDecimalArray a -> Optional.of(a.buf());
            default -> Optional.empty();
        };
    }
}
