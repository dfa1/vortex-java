package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.Objects;

/// Metadata-only [BoolArray] for `vortex.constant` columns.
///
/// Holds a single boolean value broadcast across `length` logical rows. No
/// validity buffer is allocated — `getBoolean(i)` returns the stored value
/// for any valid index, replacing the `(n+7)/8`-byte `0xFF`-fill the
/// buffer-backed path required.
///
/// @param dtype  logical [DType.Bool] type
/// @param length total logical row count
/// @param value  broadcast value
public record LazyConstantBoolArray(DType dtype, long length, boolean value) implements BoolArray {

    @Override
    public boolean getBoolean(long i) {
        Objects.checkIndex(i, length);
        return value;
    }

    @Override
    public void forEachBoolean(BooleanConsumer c) {
        long n = length;
        boolean v = value;
        for (long i = 0; i < n; i++) {
            c.accept(v);
        }
    }
}
