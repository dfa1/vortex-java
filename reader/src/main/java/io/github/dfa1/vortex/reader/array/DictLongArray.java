package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Dict-encoded [LongArray] view. ADR 0012 shape.
///
/// Stores `values` (the dictionary pool) and `codes` (one index per
/// row into `values`). Scalar access resolves on demand:
/// `getLong(i) = values.getLong(codes.getCode(i))`. Per ADR 0012, this
/// preserves zero-copy on dict-encoded categorical columns: no expansion of
/// `values` into a per-row buffer happens until a downstream caller
/// genuinely requires a contiguous segment.
///
/// The `codes` array is typed as [Array] because the codes ptype
/// varies with dictionary size — U8/U16/U32/U64 backed by
/// [ByteArray]/[ShortArray]/[IntArray]/[LongArray].
/// [#of] validates that `codes` is one of those four types.
///
/// @param dtype  logical element type (matches `values.dtype()`)
/// @param length total logical row count (matches `codes.length()`)
/// @param values dictionary pool — element at code `c` is `values.getLong(c)`
/// @param codes  per-row index into `values`; must be one of
///               [ByteArray], [ShortArray], [IntArray], [LongArray]
public record DictLongArray(DType dtype, long length, LongArray values, Array codes) implements LongArray {

    /// Builds a [DictLongArray], validating that `codes` is one of the
    /// four narrow-int code array types and that its length matches `length`.
    ///
    /// @param dtype  logical element type
    /// @param length total logical row count
    /// @param values dictionary pool
    /// @param codes  per-row code array (must be [ByteArray], [ShortArray],
    ///               [IntArray], or [LongArray])
    /// @return a new [DictLongArray]
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

    /// Materializes by gathering one dictionary value per code into a fresh
    /// little-endian `i64` segment. The codes switch is hoisted outside the loop so
    /// each branch is a uniform gather over a single code width.
    ///
    /// @param arena allocator for the output segment
    /// @return a read-only little-endian `i64` segment of gathered values
    /// @throws VortexException if `codes` is not a supported code-array type
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n * 8L, 8);
        long[] at = {0};
        forEachLong(v -> dst.setAtIndex(VortexFormat.LE_LONG, at[0]++, v));
        return dst.asReadOnly();
    }

    /// Walks the codes child sequentially through its own typed `forEach` rather than by index.
    /// Indexed access re-resolves each position from scratch, which for a chunked run-end codes
    /// child meant two binary searches per row (chunk lookup, then run lookup) — the dominant cost
    /// of scanning a dict column. `DictArrays#validateCodes` guarantees `codes.length() == length`,
    /// so a full sequential walk emits exactly the rows an indexed loop would.
    ///
    /// @param cons consumer that receives each decoded long value
    @Override
    public void forEachLong(LongConsumer cons) {
        LongArray vals = values;
        switch (codes) {
            case ByteArray ba -> ba.forEachByte(c -> cons.accept(vals.getLong(Byte.toUnsignedLong(c))));
            case ShortArray sa -> sa.forEachShort(c -> cons.accept(vals.getLong(Short.toUnsignedLong(c))));
            case IntArray ia -> ia.forEachInt(c -> cons.accept(vals.getLong(Integer.toUnsignedLong(c))));
            case LongArray la -> la.forEachLong(c -> cons.accept(vals.getLong(c)));
            default -> throw new VortexException("DictLongArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long[] acc = {identity};
        forEachLong(v -> acc[0] = op.applyAsLong(acc[0], v));
        return acc[0];
    }
}
