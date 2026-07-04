package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.DoubleBinaryOperator;

/// Dict-encoded [FloatArray] view. ADR 0012 shape.
///
/// Stores `values` (the dictionary pool) and `codes` (one index per
/// row into `values`). Scalar access resolves on demand:
/// `getFloat(i) = values.getFloat(codes.getCode(i))`. Per ADR 0012,
/// this preserves zero-copy on dict-encoded categorical columns.
///
/// The `codes` array is typed as [Array] because the codes ptype
/// varies with dictionary size — U8/U16/U32/U64 backed by
/// [ByteArray]/[ShortArray]/[IntArray]/[LongArray].
///
/// @param dtype  logical element type (matches `values.dtype()`)
/// @param length total logical row count (matches `codes.length()`)
/// @param values dictionary pool — element at code `c` is `values.getFloat(c)`
/// @param codes  per-row index into `values`; must be one of
///               [ByteArray], [ShortArray], [IntArray], [LongArray]
public record DictFloatArray(DType dtype, long length, FloatArray values, Array codes) implements FloatArray {

    /// Builds a [DictFloatArray], validating that `codes` is one of the
    /// four narrow-int code array types and that its length matches `length`.
    ///
    /// @param dtype  logical element type
    /// @param length total logical row count
    /// @param values dictionary pool
    /// @param codes  per-row code array (must be [ByteArray], [ShortArray],
    ///               [IntArray], or [LongArray])
    /// @return a new [DictFloatArray]
    /// @throws VortexException if `codes` is not a supported code-array type or
    ///                         its length does not equal `length`
    public static DictFloatArray of(DType dtype, long length, FloatArray values, Array codes) {
        DictArrays.validateCodes(codes, length);
        return new DictFloatArray(dtype, length, values, codes);
    }

    @Override
    public float getFloat(long i) {
        return values.getFloat(DictArrays.readCode(codes, i));
    }

    /// Materializes by gathering one dictionary value per code into a fresh
    /// little-endian `f32` segment. The codes switch is hoisted outside the loop so
    /// each branch is a uniform gather over a single code width.
    ///
    /// @param arena allocator for the output segment
    /// @return a read-only little-endian `f32` segment of gathered values
    /// @throws VortexException if `codes` is not a supported code-array type
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n * 4L, 4);
        FloatArray vals = values;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_FLOAT, i, vals.getFloat(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_FLOAT, i, vals.getFloat(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_FLOAT, i, vals.getFloat(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_FLOAT, i, vals.getFloat(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictFloatArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return dst.asReadOnly();
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
