package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Dict-encoded {@link LongArray} view. ADR 0012 shape.
///
/// Stores `values` (the dictionary pool) and `codes` (one index per
/// row into `values`). Scalar access resolves on demand:
/// `getLong(i) = values.getLong(codes.getCode(i))`. Per ADR 0012, this
/// preserves zero-copy on dict-encoded categorical columns: no expansion of
/// `values` into a per-row buffer happens until a downstream caller
/// genuinely requires a contiguous segment.
///
/// The `codes` array is typed as {@link Array} because the codes ptype
/// varies with dictionary size — U8/U16/U32/U64 backed by
/// {@link ByteArray}/{@link ShortArray}/{@link IntArray}/{@link LongArray}.
/// {@link #of} validates that `codes` is one of those four types.
///
/// @param dtype  logical element type (matches `values.dtype()`)
/// @param length total logical row count (matches `codes.length()`)
/// @param values dictionary pool — element at code `c` is `values.getLong(c)`
/// @param codes  per-row index into `values`; must be one of
///               {@link ByteArray}, {@link ShortArray}, {@link IntArray}, {@link LongArray}
public record DictLongArray(DType dtype, long length, LongArray values, Array codes) implements LongArray {

    /// Builds a {@link DictLongArray}, validating that `codes` is one of the
    /// four narrow-int code array types and that its length matches `length`.
    ///
    /// @param dtype  logical element type
    /// @param length total logical row count
    /// @param values dictionary pool
    /// @param codes  per-row code array (must be {@link ByteArray}, {@link ShortArray},
    ///               {@link IntArray}, or {@link LongArray})
    /// @return a new {@link DictLongArray}
    /// @throws VortexException if `codes` is not a supported code-array type or
    ///                         its length does not equal `length`
    public static DictLongArray of(DType dtype, long length, LongArray values, Array codes) {
        DictArrays.validateCodes(codes, length);
        return new DictLongArray(dtype, length, values, codes);
    }

    @Override
    public long getLong(long i) {
        return values.getLong(DictArrays.readCode(codes, i));
    }

    @Override
    public void forEachLong(LongConsumer cons) {
        long n = length;
        LongArray vals = values;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getLong(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getLong(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getLong(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getLong(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictLongArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long n = length;
        LongArray vals = values;
        long result = identity;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsLong(result, vals.getLong(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsLong(result, vals.getLong(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsLong(result, vals.getLong(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsLong(result, vals.getLong(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictLongArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return result;
    }
}
