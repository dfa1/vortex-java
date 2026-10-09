package io.github.dfa1.vortex.reader.array;

import java.lang.foreign.SegmentAllocator;

/// Decodes a lazy numeric array once into a flat buffer, as Rust executes an array to its canonical
/// form before slicing it or gathering through it.
///
/// A lazy composite (run-end, sparse, chunked, dictionary, frame-of-reference, ...) answers
/// [LongArray#getLong(long)] and friends by re-resolving each position from scratch, which for a
/// run-end or sparse child is a binary search per row. A consumer that reads every row by index
/// (a slice per scan window, a dictionary gathering through its codes) pays that per row; a single
/// [Array#materialize(SegmentAllocator)] walks the children sequentially instead.
///
/// **Vortex-internal.**
public final class CanonicalArrays {

    private CanonicalArrays() {
    }

    /// Returns `array` backed by a flat buffer: segment-backed and non-numeric arrays unchanged, a
    /// [MaskedArray] with its inner array canonicalized and its validity kept, and any other numeric
    /// array materialized into `allocator`.
    ///
    /// @param array     the array to canonicalize
    /// @param allocator allocator for the materialized buffer, which must outlive the result
    /// @return an array with the same dtype, length and values, cheap to read by index
    public static Array of(Array array, SegmentAllocator allocator) {
        if (array instanceof MaskedArray m) {
            return new MaskedArray(of(m.inner(), allocator), m.validity());
        }
        if (array.segmentIfPresent().isPresent()) {
            return array;
        }
        return switch (array) {
            case LongArray a -> new MaterializedLongArray(a.dtype(), a.length(), a.materialize(allocator));
            case IntArray a -> new MaterializedIntArray(a.dtype(), a.length(), a.materialize(allocator));
            case DoubleArray a -> new MaterializedDoubleArray(a.dtype(), a.length(), a.materialize(allocator));
            case FloatArray a -> new MaterializedFloatArray(a.dtype(), a.length(), a.materialize(allocator));
            case ShortArray a -> new MaterializedShortArray(a.dtype(), a.length(), a.materialize(allocator));
            case ByteArray a -> new MaterializedByteArray(a.dtype(), a.length(), a.materialize(allocator));
            default -> array;
        };
    }
}
