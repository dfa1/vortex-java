package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.Objects;
import java.util.function.DoubleBinaryOperator;

/// Metadata-only [FloatArray] for `vortex.constant` columns.
///
/// Holds a single F32 value broadcast across `length` logical rows. No buffer
/// is allocated — `getFloat(i)` returns the stored value for any valid index.
///
/// @param dtype  logical primitive type (F32)
/// @param length total logical row count
/// @param value  broadcast value
public record LazyConstantFloatArray(DType dtype, long length, float value) implements FloatArray {

    @Override
    public float getFloat(long i) {
        Objects.checkIndex(i, length);
        return value;
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        long n = length;
        float v = value;
        double acc = identity;
        for (long i = 0; i < n; i++) {
            acc = op.applyAsDouble(acc, v);
        }
        return acc;
    }
}
