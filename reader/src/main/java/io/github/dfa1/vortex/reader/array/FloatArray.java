package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.DoubleBinaryOperator;

/// [Array] for F32 primitive columns.
///
/// The default impl is [MaterializedFloatArray], a buffer-backed record
/// returned when an encoding decoder either materialises values eagerly or
/// has no lazy variant of its own.
public non-sealed interface FloatArray extends Array {

    /// Returns the float value at the given index.
    ///
    /// @param i zero-based index (must be in `[0, length())`)
    /// @return the float value at position `i`
    float getFloat(long i);

    /// Folds all elements using the given binary operator and identity value.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to the accumulator and each float element (widened to double)
    /// @return the final accumulated result
    default double fold(double identity, DoubleBinaryOperator op) {
        long n = length();
        double result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsDouble(result, getFloat(i));
        }
        return result;
    }

    /// Zero-copy truncation: a length-capping view over this array.
    /// [ChunkedFloatArray] overrides to keep batch iteration.
    ///
    /// @param rows number of leading elements to keep
    /// @return a length-`rows` float array view
    @Override
    default Array limited(long rows) {
        return new OffsetFloatArray(dtype(), rows, this, 0);
    }

    /// Scalar fallback: decodes every element through [#getFloat(long)] into a fresh
    /// little-endian segment. Buffer-backed ([MaterializedFloatArray]) and lazy
    /// formula-based variants ([LazyAlpFloatArray], …) override with a zero-copy or
    /// vectorised path.
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `f32` segment of `length()` elements
    @Override
    default MemorySegment materialize(SegmentAllocator arena) {
        long n = length();
        MemorySegment dst = arena.allocate(n * 4L, 4);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_FLOAT, i, getFloat(i));
        }
        return dst;
    }
}
