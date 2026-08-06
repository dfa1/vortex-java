package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;

import java.util.Objects;
import java.util.function.LongBinaryOperator;

/// Metadata-only [ByteArray] for `vortex.sequence` columns: `A[i] = base + i * multiplier`.
///
/// The encoding carries no buffers at all — base and multiplier live in proto3 metadata — so
/// every row is computable in O(1) and no allocation is needed regardless of row count.
///
/// The sum is computed in `long` and narrowed, matching the eager decode this replaces.
/// [#fold(long, LongBinaryOperator)] zero-extends for U8 columns, like the buffer-backed
/// `MaterializedByteArray`.
///
/// @param dtype      logical primitive type (I8 / U8)
/// @param length     total logical row count
/// @param base       value at row 0
/// @param multiplier step added per row
public record LazySequenceByteArray(DType dtype, long length, long base, long multiplier)
        implements ByteArray {

    @Override
    public byte getByte(long i) {
        Objects.checkIndex(i, length);
        return (byte) (base + i * multiplier);
    }

    @Override
    public void forEachByte(ByteConsumer c) {
        long n = length;
        long b = base;
        long m = multiplier;
        for (long i = 0; i < n; i++) {
            c.accept((byte) (b + i * m));
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U8;
        long n = length;
        long b = base;
        long m = multiplier;
        long acc = identity;
        for (long i = 0; i < n; i++) {
            byte raw = (byte) (b + i * m);
            acc = op.applyAsLong(acc, unsigned ? Byte.toUnsignedLong(raw) : raw);
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
        return rows >= length ? this : new LazySequenceByteArray(dtype, rows, base, multiplier);
    }
}
