package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.util.function.LongBinaryOperator;

/// Metadata-only [ByteArray] for `vortex.constant` columns.
///
/// Holds a single I8 / U8 value broadcast across `length` logical rows. No
/// buffer is allocated — `getByte(i)` / `getInt(i)` return the stored value
/// for any valid index, applying zero-extend widening for U8 columns just
/// like the buffer-backed `MaterializedByteArray`.
///
/// @param dtype  logical primitive type (I8 / U8)
/// @param length total logical row count
/// @param value  broadcast value (raw signed byte bits)
public record LazyConstantByteArray(DType dtype, long length, byte value) implements ByteArray {

    @Override
    public byte getByte(long i) {
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("index " + i + " out of bounds for length " + length);
        }
        return value;
    }

    @Override
    public int getInt(long i) {
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("index " + i + " out of bounds for length " + length);
        }
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U8;
        return unsigned ? Byte.toUnsignedInt(value) : (int) value;
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U8;
        long widened = unsigned ? Byte.toUnsignedLong(value) : (long) value;
        long n = length;
        long acc = identity;
        for (long i = 0; i < n; i++) {
            acc = op.applyAsLong(acc, widened);
        }
        return acc;
    }
}
