package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.function.LongBinaryOperator;

/// Sliced view over a [ByteArray]: `getByte(i) = inner.getByte(i + offset)`.
///
/// @param dtype  logical element type
/// @param length number of logical elements in this slice
/// @param inner  underlying byte array
/// @param offset starting index into `inner`
public record OffsetByteArray(DType dtype, long length, ByteArray inner, long offset)
        implements ByteArray {

    @Override
    public byte getByte(long i) {
        return inner.getByte(i + offset);
    }

    @Override
    public int getInt(long i) {
        return inner.getInt(i + offset);
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long acc = identity;
        for (long i = 0; i < length; i++) {
            acc = op.applyAsLong(acc, getByte(i));
        }
        return acc;
    }
}
