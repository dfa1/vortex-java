package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;

import java.util.Objects;
import java.util.function.LongBinaryOperator;

/// Metadata-only [ShortArray] for `vortex.constant` columns.
///
/// Holds a single I16 / U16 value broadcast across `length` logical rows. No
/// buffer is allocated — `getShort(i)` / `getInt(i)` return the stored value
/// for any valid index, applying zero-extend widening for U16 columns just
/// like the buffer-backed `MaterializedShortArray`.
///
/// @param dtype  logical primitive type (I16 / U16)
/// @param length total logical row count
/// @param value  broadcast value (raw signed short bits)
public record LazyConstantShortArray(DType dtype, long length, short value) implements ShortArray {

    @Override
    public short getShort(long i) {
        Objects.checkIndex(i, length);
        return value;
    }

    @Override
    public int getInt(long i) {
        Objects.checkIndex(i, length);
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U16;
        return unsigned ? Short.toUnsignedInt(value) : (int) value;
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U16;
        long widened = unsigned ? Short.toUnsignedLong(value) : (long) value;
        long n = length;
        long acc = identity;
        for (long i = 0; i < n; i++) {
            acc = op.applyAsLong(acc, widened);
        }
        return acc;
    }
}
