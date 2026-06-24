package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Dict-encoded [DoubleArray] view. ADR 0012 shape.
///
/// Stores `values` (the dictionary pool) and `codes` (one index per
/// row into `values`). Scalar access resolves on demand:
/// `getDouble(i) = values.getDouble(codes.getCode(i))`. Per ADR 0012,
/// this preserves zero-copy on dict-encoded categorical columns.
///
/// The `codes` array is typed as [Array] because the codes ptype
/// varies with dictionary size — U8/U16/U32/U64 backed by
/// [ByteArray]/[ShortArray]/[IntArray]/[LongArray].
///
/// @param dtype  logical element type (matches `values.dtype()`)
/// @param length total logical row count (matches `codes.length()`)
/// @param values dictionary pool — element at code `c` is `values.getDouble(c)`
/// @param codes  per-row index into `values`; must be one of
///               [ByteArray], [ShortArray], [IntArray], [LongArray]
public record DictDoubleArray(DType dtype, long length, DoubleArray values, Array codes) implements DoubleArray {

    /// Builds a [DictDoubleArray], validating that `codes` is one of the
    /// four narrow-int code array types and that its length matches `length`.
    ///
    /// @param dtype  logical element type
    /// @param length total logical row count
    /// @param values dictionary pool
    /// @param codes  per-row code array (must be [ByteArray], [ShortArray],
    ///               [IntArray], or [LongArray])
    /// @return a new [DictDoubleArray]
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

    /// Materialises by gathering one dictionary value per code into a fresh
    /// little-endian `f64` segment. The codes switch is hoisted outside the loop so
    /// each branch is a uniform gather over a single code width.
    ///
    /// @param arena allocator for the output segment
    /// @return a read-only little-endian `f64` segment of gathered values
    /// @throws VortexException if `codes` is not a supported code-array type
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n * 8L, 8);
        DoubleArray vals = values;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_DOUBLE, i, vals.getDouble(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_DOUBLE, i, vals.getDouble(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_DOUBLE, i, vals.getDouble(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_DOUBLE, i, vals.getDouble(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictDoubleArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return dst.asReadOnly();
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
