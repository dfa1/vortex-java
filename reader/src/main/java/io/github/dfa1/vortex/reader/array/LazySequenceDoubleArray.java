package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.Objects;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Metadata-only [DoubleArray] for `vortex.sequence` columns: `A[i] = base + i * multiplier`.
///
/// The encoding carries no buffers at all — base and multiplier live in proto3 metadata — so
/// every row is computable in O(1) and no allocation is needed regardless of row count.
///
/// The row index is converted to `double` before the multiply, matching the eager decode this
/// replaces — so results stay bit-identical, including the precision loss past 2^53 rows.
///
/// @param dtype      logical primitive type (F64)
/// @param length     total logical row count
/// @param base       value at row 0
/// @param multiplier step added per row
public record LazySequenceDoubleArray(DType dtype, long length, double base, double multiplier)
        implements DoubleArray {

    @Override
    public double getDouble(long i) {
        Objects.checkIndex(i, length);
        return base + i * multiplier;
    }

    @Override
    public void forEachDouble(DoubleConsumer c) {
        long n = length;
        double b = base;
        double m = multiplier;
        for (long i = 0; i < n; i++) {
            c.accept(b + i * m);
        }
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        long n = length;
        double b = base;
        double m = multiplier;
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
        return rows >= length ? this : new LazySequenceDoubleArray(dtype, rows, base, multiplier);
    }
}
