package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.function.DoubleBinaryOperator;

/// Dict-encoded {@link FloatArray} view. ADR 0012 shape.
///
/// Stores {@code values} (the dictionary pool) and {@code codes} (one index per
/// row into {@code values}). Scalar access resolves on demand:
/// {@code getFloat(i) = values.getFloat(codes.getCode(i))}. Per ADR 0012,
/// this preserves zero-copy on dict-encoded categorical columns.
///
/// The {@code codes} array is typed as {@link Array} because the codes ptype
/// varies with dictionary size — U8/U16/U32/U64 backed by
/// {@link ByteArray}/{@link ShortArray}/{@link IntArray}/{@link LongArray}.
///
/// @param dtype  logical element type (matches {@code values.dtype()})
/// @param length total logical row count (matches {@code codes.length()})
/// @param values dictionary pool — element at code {@code c} is {@code values.getFloat(c)}
/// @param codes  per-row index into {@code values}; must be one of
///               {@link ByteArray}, {@link ShortArray}, {@link IntArray}, {@link LongArray}
public record DictFloatArray(DType dtype, long length, FloatArray values, Array codes) implements FloatArray {

    /// Builds a {@link DictFloatArray}, validating that {@code codes} is one of the
    /// four narrow-int code array types and that its length matches {@code length}.
    ///
    /// @param dtype  logical element type
    /// @param length total logical row count
    /// @param values dictionary pool
    /// @param codes  per-row code array (must be {@link ByteArray}, {@link ShortArray},
    ///               {@link IntArray}, or {@link LongArray})
    /// @return a new {@link DictFloatArray}
    /// @throws VortexException if {@code codes} is not a supported code-array type or
    ///                         its length does not equal {@code length}
    public static DictFloatArray of(DType dtype, long length, FloatArray values, Array codes) {
        DictArrays.validateCodes(codes, length);
        return new DictFloatArray(dtype, length, values, codes);
    }

    @Override
    public float getFloat(long i) {
        return values.getFloat(DictArrays.readCode(codes, i));
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        long n = length;
        FloatArray vals = values;
        double result = identity;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsDouble(result, vals.getFloat(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsDouble(result, vals.getFloat(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsDouble(result, vals.getFloat(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsDouble(result, vals.getFloat(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictFloatArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return result;
    }
}
