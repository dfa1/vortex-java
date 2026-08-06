package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.Objects;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Metadata-only [LongArray] for `vortex.sequence` columns: `A[i] = base + i * multiplier`.
///
/// The encoding carries no buffers at all — base and multiplier live in proto3 metadata — so
/// every row is computable in O(1) and no allocation is needed regardless of row count.
///
/// Arithmetic is `long` and wraps on overflow, matching the eager decode this replaces.
///
/// @param dtype      logical primitive type (I64 / U64)
/// @param length     total logical row count
/// @param base       value at row 0
/// @param multiplier step added per row
public record LazySequenceLongArray(DType dtype, long length, long base, long multiplier)
        implements LongArray {

    @Override
    public long getLong(long i) {
        Objects.checkIndex(i, length);
        return base + i * multiplier;
    }

    @Override
    public void forEachLong(LongConsumer c) {
        long n = length;
        long b = base;
        long m = multiplier;
        for (long i = 0; i < n; i++) {
            c.accept(b + i * m);
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long n = length;
        long b = base;
        long m = multiplier;
        long acc = identity;
        for (long i = 0; i < n; i++) {
            acc = op.applyAsLong(acc, b + i * m);
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
        return rows >= length ? this : new LazySequenceLongArray(dtype, rows, base, multiplier);
    }
}
