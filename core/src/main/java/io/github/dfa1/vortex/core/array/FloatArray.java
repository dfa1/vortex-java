package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.DoubleBinaryOperator;

/// Concrete [Array] for F32 primitive columns.
public final class FloatArray implements Array {

    private final DType dtype;
    private final long length;
    private final MemorySegment buffer;
    private final long elementCount;

    /// Creates a new {@code FloatArray} backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be F32
    /// @param length number of elements
    /// @param buffer little-endian float data (4 bytes per element)
    public FloatArray(DType dtype, long length, MemorySegment buffer) {
        this.dtype = dtype;
        this.length = length;
        this.buffer = buffer;
        this.elementCount = buffer.byteSize() / PTypeIO.LE_FLOAT.byteSize();
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public long length() {
        return length;
    }


    MemorySegment buffer() {
        return buffer;
    }

    /// Returns the float value at the given index.
    ///
    /// @param i zero-based index (must be in {@code [0, length)})
    /// @return the float value at position {@code i}
    public float getFloat(long i) {
        return buffer.getAtIndex(PTypeIO.LE_FLOAT, length == elementCount ? i : i % elementCount);
    }

    /// Folds all elements using the given binary operator and identity value.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to the accumulator and each float element (widened to double)
    /// @return the final accumulated result
    public double fold(double identity, DoubleBinaryOperator op) {
        MemorySegment buf = buffer;
        long n = length;
        double result = identity;
        if (n == elementCount) {
            for (long i = 0; i < n; i++) {
                result = op.applyAsDouble(result, buf.getAtIndex(PTypeIO.LE_FLOAT, i));
            }
        } else {
            for (long i = 0; i < n; i++) {
                result = op.applyAsDouble(result, buf.getAtIndex(PTypeIO.LE_FLOAT, i % elementCount));
            }
        }
        return result;
    }
}
