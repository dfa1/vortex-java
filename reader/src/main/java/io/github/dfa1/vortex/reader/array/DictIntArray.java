package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// Dict-encoded {@link IntArray} view. ADR 0012 shape.
///
/// Stores `values` (the dictionary pool) and `codes` (one index per
/// row into `values`). Scalar access resolves on demand:
/// `getInt(i) = values.getInt(codes.getCode(i))`. Per ADR 0012, this
/// preserves zero-copy on dict-encoded categorical columns.
///
/// The `codes` array is typed as {@link Array} because the codes ptype
/// varies with dictionary size — U8/U16/U32/U64 backed by
/// {@link ByteArray}/{@link ShortArray}/{@link IntArray}/{@link LongArray}.
///
/// @param dtype  logical element type (matches `values.dtype()`)
/// @param length total logical row count (matches `codes.length()`)
/// @param values dictionary pool — element at code `c` is `values.getInt(c)`
/// @param codes  per-row index into `values`; must be one of
///               {@link ByteArray}, {@link ShortArray}, {@link IntArray}, {@link LongArray}
public record DictIntArray(DType dtype, long length, IntArray values, Array codes) implements IntArray {

    /// Builds a {@link DictIntArray}, validating that `codes` is one of the
    /// four narrow-int code array types and that its length matches `length`.
    ///
    /// @param dtype  logical element type
    /// @param length total logical row count
    /// @param values dictionary pool
    /// @param codes  per-row code array (must be {@link ByteArray}, {@link ShortArray},
    ///               {@link IntArray}, or {@link LongArray})
    /// @return a new {@link DictIntArray}
    /// @throws VortexException if `codes` is not a supported code-array type or
    ///                         its length does not equal `length`
    public static DictIntArray of(DType dtype, long length, IntArray values, Array codes) {
        DictArrays.validateCodes(codes, length);
        return new DictIntArray(dtype, length, values, codes);
    }

    @Override
    public int getInt(long i) {
        return values.getInt(DictArrays.readCode(codes, i));
    }

    @Override
    public void forEachInt(IntConsumer cons) {
        long n = length;
        IntArray vals = values;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getInt(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getInt(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getInt(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getInt(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictIntArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
    }

    @Override
    public int fold(int identity, IntBinaryOperator op) {
        long n = length;
        IntArray vals = values;
        int result = identity;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsInt(result, vals.getInt(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsInt(result, vals.getInt(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsInt(result, vals.getInt(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsInt(result, vals.getInt(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictIntArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return result;
    }
}
