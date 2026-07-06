package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.util.function.LongBinaryOperator;

/// Dict-encoded [ByteArray] view. ADR 0012 shape.
///
/// Stores `values` (the dictionary pool) and `codes` (one index per
/// row into `values`). Scalar access resolves on demand:
/// `getByte(i) = values.getByte(codes.getCode(i))`. Per ADR 0012, this
/// preserves zero-copy on dict-encoded categorical columns.
///
/// The `codes` array is typed as [Array] because the codes ptype
/// varies with dictionary size — U8/U16/U32/U64 backed by
/// [ByteArray]/[ShortArray]/[IntArray]/[LongArray].
///
/// @param dtype  logical element type (matches `values.dtype()`)
/// @param length total logical row count (matches `codes.length()`)
/// @param values dictionary pool — element at code `c` is `values.getByte(c)`
/// @param codes  per-row index into `values`; must be one of
///               [ByteArray], [ShortArray], [IntArray], [LongArray]
public record DictByteArray(DType dtype, long length, ByteArray values, Array codes) implements ByteArray {

    /// Builds a [DictByteArray], validating that `codes` is one of the
    /// four narrow-int code array types and that its length matches `length`.
    ///
    /// @param dtype  logical element type
    /// @param length total logical row count
    /// @param values dictionary pool
    /// @param codes  per-row code array (must be [ByteArray], [ShortArray],
    ///               [IntArray], or [LongArray])
    /// @return a new [DictByteArray]
    /// @throws VortexException if `codes` is not a supported code-array type or
    ///                         its length does not equal `length`
    public static DictByteArray of(DType dtype, long length, ByteArray values, Array codes) {
        DictArrays.validateCodes(codes, length);
        return new DictByteArray(dtype, length, values, codes);
    }

    @Override
    public byte getByte(long i) {
        return values.getByte(DictArrays.readCode(codes, i));
    }

    /// Materializes by gathering one dictionary value per code into a fresh
    /// one-byte-per-element segment. The codes switch is hoisted outside the loop so
    /// each branch is a uniform gather over a single code width.
    ///
    /// @param arena allocator for the output segment
    /// @return a read-only segment of `length()` gathered bytes
    /// @throws VortexException if `codes` is not a supported code-array type
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n);
        ByteArray vals = values;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    dst.set(ValueLayout.JAVA_BYTE, i, vals.getByte(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    dst.set(ValueLayout.JAVA_BYTE, i, vals.getByte(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    dst.set(ValueLayout.JAVA_BYTE, i, vals.getByte(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    dst.set(ValueLayout.JAVA_BYTE, i, vals.getByte(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictByteArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return dst.asReadOnly();
    }

    @Override
    public void forEachByte(ByteConsumer cons) {
        long n = length;
        ByteArray vals = values;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getByte(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getByte(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getByte(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    cons.accept(vals.getByte(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictByteArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long n = length;
        ByteArray vals = values;
        long result = identity;
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsLong(result, vals.getByte(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsLong(result, vals.getByte(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsLong(result, vals.getByte(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    result = op.applyAsLong(result, vals.getByte(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictByteArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return result;
    }
}
