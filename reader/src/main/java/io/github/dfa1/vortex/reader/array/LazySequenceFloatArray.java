package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.Objects;
import java.util.function.DoubleBinaryOperator;

/// Metadata-only [FloatArray] for `vortex.sequence` columns: `A[i] = base + i * multiplier`.
///
/// The encoding carries no buffers at all — base and multiplier live in proto3 metadata — so
/// every row is computable in O(1) and no allocation is needed regardless of row count.
///
/// The arithmetic stays in `float`, matching the eager decode this replaces — so results stay
/// bit-identical rather than being sharpened by a wider accumulator.
///
/// @param dtype      logical primitive type (F32)
/// @param length     total logical row count
/// @param base       value at row 0
/// @param multiplier step added per row
public record LazySequenceFloatArray(DType dtype, long length, float base, float multiplier)
        implements FloatArray {

    @Override
    public float getFloat(long i) {
        Objects.checkIndex(i, length);
        return base + i * multiplier;
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        long n = length;
        float b = base;
        float m = multiplier;
        double acc = identity;
        for (long i = 0; i < n; i++) {
            acc = op.applyAsDouble(acc, b + i * m);
        }
        return acc;
    }

    /// Zero-copy truncation: the formula is unchanged for the leading rows, so only the
    /// row count shrinks.
    ///
    /// @param rows number of leading rows to keep
    /// @return a length-`rows` sequence over the same base and multiplier
    @Override
    public Array limited(long rows) {
        return rows >= length ? this : new LazySequenceFloatArray(dtype, rows, base, multiplier);
    }
}
