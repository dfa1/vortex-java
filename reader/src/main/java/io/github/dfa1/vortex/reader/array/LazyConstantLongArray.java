package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.Objects;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Metadata-only [LongArray] for `vortex.constant` columns.
///
/// Holds a single I64 / U64 value broadcast across `length` logical rows. No
/// buffer is allocated — `getLong(i)` returns the stored value for any valid
/// index, letting consumers skip the per-element broadcast-modulo path that
/// the buffer-backed encoding previously forced.
///
/// `forEachLong` / `fold` are overridden to a tight constant-folded loop so the
/// JIT sees the invariant value and can hoist it out of the body.
///
/// @param dtype  logical primitive type (I64 / U64)
/// @param length total logical row count
/// @param value  broadcast value
public record LazyConstantLongArray(DType dtype, long length, long value) implements LongArray {

    @Override
    public long getLong(long i) {
        Objects.checkIndex(i, length);
        return value;
    }

    @Override
    public void forEachLong(LongConsumer c) {
        long n = length;
        long v = value;
        for (long i = 0; i < n; i++) {
            c.accept(v);
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long n = length;
        long v = value;
        long acc = identity;
        for (long i = 0; i < n; i++) {
            acc = op.applyAsLong(acc, v);
        }
        return acc;
    }
}
