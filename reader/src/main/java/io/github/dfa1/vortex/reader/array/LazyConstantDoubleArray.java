package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.Objects;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Metadata-only [DoubleArray] for `vortex.constant` columns.
///
/// Holds a single F64 value broadcast across `length` logical rows. No buffer
/// is allocated — `getDouble(i)` returns the stored value for any valid index.
///
/// @param dtype  logical primitive type (F64)
/// @param length total logical row count
/// @param value  broadcast value
public record LazyConstantDoubleArray(DType dtype, long length, double value) implements DoubleArray {

    @Override
    public double getDouble(long i) {
        Objects.checkIndex(i, length);
        return value;
    }

    @Override
    public void forEachDouble(DoubleConsumer c) {
        long n = length;
        double v = value;
        for (long i = 0; i < n; i++) {
            c.accept(v);
        }
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        long n = length;
        double v = value;
        double acc = identity;
        for (long i = 0; i < n; i++) {
            acc = op.applyAsDouble(acc, v);
        }
        return acc;
    }
}
