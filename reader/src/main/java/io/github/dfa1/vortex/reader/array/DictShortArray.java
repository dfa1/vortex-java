package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.LongBinaryOperator;

/// Dict-encoded [ShortArray] view. ADR 0012 shape.
///
/// Stores `values` (the dictionary pool) and `codes` (one index per
/// row into `values`). Scalar access resolves on demand:
/// `getShort(i) = values.getShort(codes.getCode(i))`. Per ADR 0012, this
/// preserves zero-copy on dict-encoded categorical columns.
///
/// The `codes` array is typed as [Array] because the codes ptype
/// varies with dictionary size — U8/U16/U32/U64 backed by
/// [ByteArray]/[ShortArray]/[IntArray]/[LongArray].
///
/// @param dtype  logical element type (matches `values.dtype()`)
/// @param length total logical row count (matches `codes.length()`)
/// @param values dictionary pool — element at code `c` is `values.getShort(c)`
/// @param codes  per-row index into `values`; must be one of
///               [ByteArray], [ShortArray], [IntArray], [LongArray]
public record DictShortArray(DType dtype, long length, ShortArray values, Array codes) implements ShortArray {

    /// Builds a [DictShortArray], validating that `codes` is one of the
    /// four narrow-int code array types and that its length matches `length`.
    ///
    /// @param dtype  logical element type
    /// @param length total logical row count
    /// @param values dictionary pool
    /// @param codes  per-row code array (must be [ByteArray], [ShortArray],
    ///               [IntArray], or [LongArray])
    /// @return a new [DictShortArray]
    /// @throws VortexException if `codes` is not a supported code-array type or
    ///                         its length does not equal `length`
    public static DictShortArray of(DType dtype, long length, ShortArray values, Array codes) {
        DictArrays.validateCodes(codes, length);
        return new DictShortArray(dtype, length, values, codes);
    }

    @Override
    public short getShort(long i) {
        return values.getShort(DictArrays.readCode(codes, i));
    }

    /// Materializes by gathering one dictionary value per code into a fresh
    /// little-endian `i16` segment. The codes switch is hoisted outside the loop so
    /// each branch is a uniform gather over a single code width.
    ///
    /// @param arena allocator for the output segment
    /// @return a read-only little-endian `i16` segment of gathered values
    /// @throws VortexException if `codes` is not a supported code-array type
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n * 2L, 2);
        ShortArray vals = values;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_SHORT, i, vals.getShort(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_SHORT, i, vals.getShort(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_SHORT, i, vals.getShort(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_SHORT, i, vals.getShort(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictShortArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return dst.asReadOnly();
    }

    @Override
    public void forEachShort(ShortConsumer cons) {
        long n = length;
        ShortArray vals = values;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getShort(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getShort(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getShort(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getShort(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictShortArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long n = length;
        ShortArray vals = values;
        long result = identity;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsLong(result, vals.getShort(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsLong(result, vals.getShort(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsLong(result, vals.getShort(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsLong(result, vals.getShort(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictShortArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return result;
    }
}
