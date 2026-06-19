package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// [Array] for I64/U64 primitive columns.
///
/// The default impl is [MaterializedLongArray], a buffer-backed record
/// returned when an encoding decoder either materialises values eagerly or
/// has no lazy variant of its own.
public non-sealed interface LongArray extends Array {

    /// Returns the long value at the given index.
    ///
    /// @param i zero-based index (must be in `[0, length())`)
    /// @return the long value at position `i`
    long getLong(long i);

    /// Passes each element to the given consumer in order.
    ///
    /// @param c consumer that receives each long element
    default void forEachLong(LongConsumer c) {
        long n = length();
        for (long i = 0; i < n; i++) {
            c.accept(getLong(i));
        }
    }

    /// Folds all elements using the given binary operator and identity value.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to the accumulator and each long element
    /// @return the final accumulated result
    default long fold(long identity, LongBinaryOperator op) {
        long n = length();
        long result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsLong(result, getLong(i));
        }
        return result;
    }

    /// Zero-copy truncation: a length-capping view over this array (no buffer is
    /// copied). [ChunkedLongArray] overrides to keep batch iteration.
    ///
    /// @param rows number of leading elements to keep
    /// @return a length-`rows` long array view
    @Override
    default Array limited(long rows) {
        return new OffsetLongArray(dtype(), rows, this, 0);
    }

    /// Scalar fallback: decodes every element through [#getLong(long)] into a fresh
    /// little-endian segment. Buffer-backed ([MaterializedLongArray]) and lazy
    /// formula-based variants ([LazyForLongArray], [LazyZigZagLongArray], …)
    /// override with a zero-copy or vectorised path.
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `i64` segment of `length()` elements
    @Override
    default MemorySegment materialize(SegmentAllocator arena) {
        long n = length();
        MemorySegment dst = arena.allocate(n * 8L, 8);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_LONG, i, getLong(i));
        }
        return dst;
    }
}
