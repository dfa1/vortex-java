package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.io.VortexFormat;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// [Array] for I32/U32 primitive columns.
///
/// The default impl is [MaterializedIntArray], a buffer-backed record
/// returned when an encoding decoder either materializes values eagerly or
/// has no lazy variant of its own.
public non-sealed interface IntArray extends Array {

    /// Returns the int value at the given index.
    ///
    /// @param i zero-based index (must be in `[0, length())`)
    /// @return the int value at position `i`
    int getInt(long i);

    /// Passes each element to the given consumer in order.
    ///
    /// @param c consumer that receives each int element
    default void forEachInt(IntConsumer c) {
        long n = length();
        for (long i = 0; i < n; i++) {
            c.accept(getInt(i));
        }
    }

    /// Folds all elements using the given binary operator and identity value.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to the accumulator and each int element
    /// @return the final accumulated result
    default int fold(int identity, IntBinaryOperator op) {
        long n = length();
        int result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsInt(result, getInt(i));
        }
        return result;
    }

    /// Zero-copy truncation: a length-capping view over this array.
    /// [ChunkedIntArray] overrides to keep batch iteration.
    ///
    /// @param rows number of leading elements to keep
    /// @return a length-`rows` int array view
    @Override
    default Array limited(long rows) {
        return new OffsetIntArray(dtype(), rows, this, 0);
    }

    /// Scalar fallback: decodes every element through [#getInt(long)] into a fresh
    /// little-endian segment. Buffer-backed ([MaterializedIntArray]) and lazy
    /// formula-based variants ([LazyForIntArray], [LazyZigZagIntArray], …) override
    /// with a zero-copy or vectorized path.
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `i32` segment of `length()` elements
    @Override
    default MemorySegment materialize(SegmentAllocator arena) {
        long n = length();
        MemorySegment dst = arena.allocate(n * 4L, 4);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(VortexFormat.LE_INT, i, getInt(i));
        }
        return dst;
    }
}
