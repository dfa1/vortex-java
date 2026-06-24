package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.Objects;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// Metadata-only [IntArray] for `vortex.constant` columns.
///
/// Holds a single I32 / U32 value broadcast across `length` logical rows. No
/// buffer is allocated — `getInt(i)` returns the stored value for any valid
/// index, letting consumers skip the per-element broadcast-modulo path.
///
/// @param dtype  logical primitive type (I32 / U32)
/// @param length total logical row count
/// @param value  broadcast value
public record LazyConstantIntArray(DType dtype, long length, int value) implements IntArray {

    @Override
    public int getInt(long i) {
        Objects.checkIndex(i, length);
        return value;
    }

    @Override
    public void forEachInt(IntConsumer c) {
        long n = length;
        int v = value;
        for (long i = 0; i < n; i++) {
            c.accept(v);
        }
    }

    @Override
    public int fold(int identity, IntBinaryOperator op) {
        long n = length;
        int v = value;
        int acc = identity;
        for (long i = 0; i < n; i++) {
            acc = op.applyAsInt(acc, v);
        }
        return acc;
    }
}
