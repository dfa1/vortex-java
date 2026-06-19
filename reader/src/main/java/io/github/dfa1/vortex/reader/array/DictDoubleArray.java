package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Dict-encoded [DoubleArray] view. ADR 0012 shape.
///
/// Stores `values` (the dictionary pool) and `codes` (one index per
/// row into `values`). Scalar access resolves on demand:
/// `getDouble(i) = values.getDouble(codes.getCode(i))`. Per ADR 0012,
/// this preserves zero-copy on dict-encoded categorical columns.
///
/// The `codes` array is typed as {@link Array} because the codes ptype
/// varies with dictionary size — U8/U16/U32/U64 backed by
/// {@link ByteArray}/{@link ShortArray}/{@link IntArray}/{@link LongArray}.
///
/// @param dtype  logical element type (matches `values.dtype()`)
/// @param length total logical row count (matches `codes.length()`)
/// @param values dictionary pool — element at code `c` is `values.getDouble(c)`
/// @param codes  per-row index into `values`; must be one of
///               {@link ByteArray}, {@link ShortArray}, {@link IntArray}, {@link LongArray}
public record DictDoubleArray(DType dtype, long length, DoubleArray values, Array codes) implements DoubleArray {

    /// Builds a [DictDoubleArray], validating that `codes` is one of the
    /// four narrow-int code array types and that its length matches `length`.
    ///
    /// @param dtype  logical element type
    /// @param length total logical row count
    /// @param values dictionary pool
    /// @param codes  per-row code array (must be {@link ByteArray}, {@link ShortArray},
    ///               {@link IntArray}, or {@link LongArray})
    /// @return a new {@link DictDoubleArray}
    /// @throws VortexException if `codes` is not a supported code-array type or
    ///                         its length does not equal `length`
    public static DictDoubleArray of(DType dtype, long length, DoubleArray values, Array codes) {
        DictArrays.validateCodes(codes, length);
        return new DictDoubleArray(dtype, length, values, codes);
    }

    @Override
    public double getDouble(long i) {
        return values.getDouble(DictArrays.readCode(codes, i));
    }

    @Override
    public void forEachDouble(DoubleConsumer cons) {
        long n = length;
        DoubleArray vals = values;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getDouble(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getDouble(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getDouble(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getDouble(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictDoubleArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        long n = length;
        DoubleArray vals = values;
        double result = identity;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsDouble(result, vals.getDouble(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsDouble(result, vals.getDouble(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsDouble(result, vals.getDouble(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsDouble(result, vals.getDouble(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictDoubleArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return result;
    }
}
