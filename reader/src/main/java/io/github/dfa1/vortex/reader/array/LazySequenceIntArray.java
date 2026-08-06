package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.Objects;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// Metadata-only [IntArray] for `vortex.sequence` columns: `A[i] = base + i * multiplier`.
///
/// The encoding carries no buffers at all — base and multiplier live in proto3 metadata — so
/// every row is computable in O(1) and no allocation is needed regardless of row count.
///
/// The sum is computed in `long` and narrowed, matching the eager decode this replaces.
///
/// @param dtype      logical primitive type (I32 / U32)
/// @param length     total logical row count
/// @param base       value at row 0
/// @param multiplier step added per row
public record LazySequenceIntArray(DType dtype, long length, long base, long multiplier)
        implements IntArray {

    @Override
    public int getInt(long i) {
        Objects.checkIndex(i, length);
        return (int) (base + i * multiplier);
    }

    @Override
    public void forEachInt(IntConsumer c) {
        long n = length;
        long b = base;
        long m = multiplier;
        for (long i = 0; i < n; i++) {
            c.accept((int) (b + i * m));
        }
    }

    @Override
    public int fold(int identity, IntBinaryOperator op) {
        long n = length;
        long b = base;
        long m = multiplier;
        int acc = identity;
        for (long i = 0; i < n; i++) {
            acc = op.applyAsInt(acc, (int) (b + i * m));
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
        return rows >= length ? this : new LazySequenceIntArray(dtype, rows, base, multiplier);
    }
}
