package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.function.LongBinaryOperator;

/// Sliced view over a [ShortArray]: `getShort(i) = inner.getShort(i + offset)`.
///
/// @param dtype  logical element type
/// @param length number of logical elements in this slice
/// @param inner  underlying short array
/// @param offset starting index into `inner`
public record OffsetShortArray(DType dtype, long length, ShortArray inner, long offset)
        implements ShortArray {

    @Override
    public short getShort(long i) {
        return inner.getShort(i + offset);
    }

    @Override
    public int getInt(long i) {
        return inner.getInt(i + offset);
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long acc = identity;
        for (long i = 0; i < length; i++) {
            acc = op.applyAsLong(acc, getShort(i));
        }
        return acc;
    }
}
