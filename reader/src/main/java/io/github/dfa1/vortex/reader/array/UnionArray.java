package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.List;

/// Decoded `vortex.union` array, Rust's sparse `UnionArray`: a `U8` type id per row (null when the
/// union row is null) and one child per variant, each as long as the union. Row `i` is the value at
/// row `i` of the variant whose type id equals `typeId(i)`.
public final class UnionArray implements Array {

    private final DType.Union dtype;
    private final long length;
    private final Array typeIds;
    private final ByteArray typeIdValues;
    private final BoolArray validity;
    private final List<Array> variants;

    /// Creates a new `UnionArray`.
    ///
    /// @param dtype    the union dtype
    /// @param length   number of rows
    /// @param typeIds  the `U8` type id per row, a [MaskedArray] when the union is nullable
    /// @param variants one row-aligned array per variant, in the dtype's variant order
    /// @throws VortexException if the type ids are not bytes or the variant count is wrong
    public UnionArray(DType.Union dtype, long length, Array typeIds, List<Array> variants) {
        if (variants.size() != dtype.variantTypes().size()) {
            throw new VortexException(EncodingId.VORTEX_UNION, "expected " + dtype.variantTypes().size()
                    + " variant children, got " + variants.size());
        }
        MaskedArray.Unwrapped unwrapped = MaskedArray.unwrap(typeIds);
        if (!(unwrapped.inner() instanceof ByteArray bytes)) {
            throw new VortexException(EncodingId.VORTEX_UNION, "type_ids must decode to U8, got "
                    + unwrapped.inner().getClass().getSimpleName());
        }
        this.dtype = dtype;
        this.length = length;
        this.typeIds = typeIds;
        this.typeIdValues = bytes;
        this.validity = unwrapped.validity();
        this.variants = List.copyOf(variants);
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    /// Returns the per-row type ids, as decoded.
    ///
    /// @return the `U8` type ids array
    public Array typeIds() {
        return typeIds;
    }

    /// Returns the number of variants.
    ///
    /// @return the variant count
    public int variantCount() {
        return variants.size();
    }

    /// Returns the array of the variant at the given position.
    ///
    /// @param i zero-based variant index, in the dtype's variant order
    /// @return the variant's row-aligned array
    public Array variant(int i) {
        return variants.get(i);
    }

    /// Returns whether row `i` is non-null.
    ///
    /// @param i zero-based row index
    /// @return `true` if the row holds a value
    public boolean isValid(long i) {
        return validity == null || validity.getBoolean(i);
    }

    /// Returns the type id of row `i`. Meaningless for a null row.
    ///
    /// @param i zero-based row index
    /// @return the unsigned 8-bit type id
    public int typeId(long i) {
        return Byte.toUnsignedInt(typeIdValues.getByte(i));
    }

    /// Returns the position of the variant row `i` selects. Meaningless for a null row.
    ///
    /// @param i zero-based row index
    /// @return the variant index, for [#variant(int)]
    /// @throws VortexException if the row's type id names no variant
    public int variantIndex(long i) {
        int typeId = typeId(i);
        int index = dtype.variantIndex(typeId);
        if (index < 0) {
            throw new VortexException(EncodingId.VORTEX_UNION, "row " + i + " has unknown type id " + typeId);
        }
        return index;
    }

    @Override
    public Array limited(long rows) {
        return new UnionArray(dtype, rows, Array.limited(typeIds, rows),
                variants.stream().map(v -> Array.limited(v, rows)).toList());
    }

    /// Always throws: a union is type ids plus one child per variant, not a single primary
    /// segment. Materialize [#typeIds()] / [#variant(int)] separately.
    ///
    /// @param arena unused
    /// @return never returns
    /// @throws VortexException always
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        throw new VortexException("UnionArray has no primary segment");
    }
}
