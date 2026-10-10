package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Dict-encoded [Float16Array] view. ADR 0012 shape.
///
/// Stores `values` (the dictionary pool) and `codes` (one index per row into `values`). Scalar
/// access resolves on demand: `getFloat(i) = values.getFloat(codes.getCode(i))`, so a
/// categorical column costs the pool plus the codes.
///
/// The `codes` array is typed as [Array] because the codes ptype varies with dictionary size —
/// U8/U16/U32/U64 backed by [ByteArray]/[ShortArray]/[IntArray]/[LongArray].
///
/// @param dtype  logical element type (matches `values.dtype()`)
/// @param length total logical row count (matches `codes.length()`)
/// @param values dictionary pool — element at code `c` is `values.getFloat(c)`
/// @param codes  per-row index into `values`; must be one of
///               [ByteArray], [ShortArray], [IntArray], [LongArray]
public record DictFloat16Array(DType dtype, long length, Float16Array values, Array codes) implements Float16Array {

    /// Builds a [DictFloat16Array], validating that `codes` is one of the four narrow-int code
    /// array types and that its length matches `length`.
    ///
    /// @param dtype  logical element type
    /// @param length total logical row count
    /// @param values dictionary pool
    /// @param codes  per-row code array (must be [ByteArray], [ShortArray], [IntArray], or [LongArray])
    /// @return a new [DictFloat16Array]
    /// @throws VortexException if `codes` is not a supported code-array type or its length does
    ///                         not equal `length`
    public static DictFloat16Array of(DType dtype, long length, Float16Array values, Array codes) {
        DictArrays.validateCodes(codes, length);
        return new DictFloat16Array(dtype, length, values, codes);
    }

    @Override
    public float getFloat(long i) {
        return values.getFloat(DictArrays.readCode(codes, i));
    }

    /// Zero-copy truncation: the pool is shared, only the codes are cut.
    ///
    /// @param rows number of leading rows to keep
    /// @return a length-`rows` dict over the same pool
    @Override
    public Array limited(long rows) {
        return rows >= length ? this : new DictFloat16Array(dtype, rows, values, Array.limited(codes, rows));
    }

    /// Materializes by gathering one pool entry per code, copying the half's 16 bits rather than
    /// widening and narrowing them, so NaN payloads survive. The codes switch is hoisted outside
    /// the loop so each branch is a uniform gather over a single code width.
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `f16` segment of gathered values
    /// @throws VortexException if `codes` is not a supported code-array type
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n * 2L, 2);
        MemorySegment pool = values.materialize(arena);
        switch (CanonicalArrays.of(codes, arena)) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_SHORT, i,
                            pool.getAtIndex(VortexFormat.LE_SHORT, Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_SHORT, i,
                            pool.getAtIndex(VortexFormat.LE_SHORT, Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_SHORT, i,
                            pool.getAtIndex(VortexFormat.LE_SHORT, Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_SHORT, i, pool.getAtIndex(VortexFormat.LE_SHORT, la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictFloat16Array: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return dst.asReadOnly();
    }
}
