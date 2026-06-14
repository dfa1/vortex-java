package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.lang.foreign.MemorySegment;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// [Array] for F64 primitive columns.
///
/// Default impl is [MaterializedDoubleArray], a buffer-backed record returned
/// when an encoding decoder either materialises the values eagerly or has no
/// lazy variant of its own. Encoding-specific concretes (e.g.
/// [AlpDoubleArray]) implement this interface directly and compute values
/// from their encoded source on demand.
public non-sealed interface DoubleArray extends Array {

    /// Returns the double value at the given logical index.
    ///
    /// @param i zero-based logical index (must be in {@code [0, length())})
    /// @return the double value at position {@code i}
    double getDouble(long i);

    /// Invokes the consumer for each element in order.
    ///
    /// @param c consumer called once per element with the double value at each index
    void forEachDouble(DoubleConsumer c);

    /// Reduces all elements to a single double using the supplied operator.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to accumulator and each element in order
    /// @return the final accumulated value
    double fold(double identity, DoubleBinaryOperator op);

    /// Convenience factory for the materialised fallback impl.
    ///
    /// @param dtype  F64 primitive dtype
    /// @param length number of logical elements
    /// @param buffer raw double data (8 bytes per element, little-endian)
    /// @return a [MaterializedDoubleArray] backed by {@code buffer}
    static DoubleArray of(DType dtype, long length, MemorySegment buffer) {
        return new MaterializedDoubleArray(dtype, length, buffer);
    }
}
