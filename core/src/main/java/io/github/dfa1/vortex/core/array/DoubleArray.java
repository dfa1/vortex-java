package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Concrete [Array] for F64 primitive columns.
public final class DoubleArray implements Array {

    private final DType dtype;
    private final long length;
    final MemorySegment buffer;

    /// Constructs a {@code DoubleArray} backed by the given buffer.
    ///
    /// @param dtype  logical type, must be a {@link io.github.dfa1.vortex.core.DType.Primitive} with ptype F64
    /// @param length number of logical elements
    /// @param buffer raw double data (8 bytes per element, little-endian)
    public DoubleArray(DType dtype, long length, MemorySegment buffer) {
        this.dtype = dtype;
        this.length = length;
        this.buffer = buffer;
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public long length() {
        return length;
    }


    /// Returns the double value at the given logical index.
    ///
    /// @param i zero-based logical index (must be in {@code [0, length)})
    /// @return the double value at position {@code i}
    public double getDouble(long i) {
        long cap = buffer.byteSize() / PTypeIO.LE_DOUBLE.byteSize();
        return buffer.getAtIndex(PTypeIO.LE_DOUBLE, i % cap);
    }

    /// Invokes the consumer for each element in order.
    ///
    /// @param c consumer called once per element with the double value at each index
    public void forEachDouble(DoubleConsumer c) {
        MemorySegment buf = buffer;
        long n = length;
        long cap = buf.byteSize() / PTypeIO.LE_DOUBLE.byteSize();
        for (long i = 0; i < n; i++) {
            c.accept(buf.getAtIndex(PTypeIO.LE_DOUBLE, i % cap));
        }
    }

    /// Reduces all elements to a single double using the supplied operator.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to accumulator and each element in order
    /// @return the final accumulated value
    public double fold(double identity, DoubleBinaryOperator op) {
        long cap = buffer.byteSize() / PTypeIO.LE_DOUBLE.byteSize();
        double result = identity;
        for (long i = 0; i < length; i++) {
            result = op.applyAsDouble(result, buffer.getAtIndex(PTypeIO.LE_DOUBLE, i % cap));
        }
        return result;
    }
}
