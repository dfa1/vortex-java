package io.github.dfa1.vortex.reader.array;


import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// [Array] for F64 primitive columns.
///
/// The default impl is [MaterializedDoubleArray], a buffer-backed record
/// returned when an encoding decoder either materialises values eagerly or
/// has no lazy variant of its own.
public non-sealed interface DoubleArray extends Array {

    /// Returns the double value at the given logical index.
    ///
    /// @param i zero-based logical index (must be in `[0, length())`)
    /// @return the double value at position `i`
    double getDouble(long i);

    /// Invokes the consumer for each element in order.
    ///
    /// @param c consumer called once per element with the double value at each index
    default void forEachDouble(DoubleConsumer c) {
        long n = length();
        for (long i = 0; i < n; i++) {
            c.accept(getDouble(i));
        }
    }

    /// Reduces all elements to a single double using the supplied operator.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to accumulator and each element in order
    /// @return the final accumulated value
    default double fold(double identity, DoubleBinaryOperator op) {
        long n = length();
        double result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsDouble(result, getDouble(i));
        }
        return result;
    }

    /// Zero-copy truncation: a length-capping view over this array.
    /// [ChunkedDoubleArray] overrides to keep batch iteration.
    ///
    /// @param rows number of leading elements to keep
    /// @return a length-`rows` double array view
    @Override
    default Array truncate(long rows) {
        return new OffsetDoubleArray(dtype(), rows, this, 0);
    }
}
