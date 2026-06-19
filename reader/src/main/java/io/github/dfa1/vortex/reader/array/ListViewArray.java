package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Decoded variable-length list-view array (Arrow ListView layout).
///
/// Unlike [ListArray], offsets and sizes are independent per row:
/// `list[i] = elements[offsets[i] .. offsets[i] + sizes[i]]`.
/// Both `offsets` and `sizes` have length `outerLen` (not outerLen+1).
public final class ListViewArray implements Array {

    private final DType.List dtype;
    private final long outerLen;
    private final Array elements;
    private final Array offsets;
    private final Array sizes;

    /// Creates a new `ListViewArray`.
    ///
    /// @param dtype    logical list type
    /// @param outerLen number of outer list elements
    /// @param elements flat elements array
    /// @param offsets  per-row start offsets into `elements` (length `outerLen`)
    /// @param sizes    per-row element counts (length `outerLen`)
    public ListViewArray(DType.List dtype, long outerLen, Array elements, Array offsets, Array sizes) {
        this.dtype = dtype;
        this.outerLen = outerLen;
        this.elements = elements;
        this.offsets = offsets;
        this.sizes = sizes;
    }

    @Override
    public long length() {
        return outerLen;
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    /// Returns the flat elements array.
    ///
    /// @return the elements array
    public Array elements() {
        return elements;
    }

    /// Returns the per-row start offsets array (length `outerLen`).
    ///
    /// @return the offsets array
    public Array offsets() {
        return offsets;
    }

    /// Returns the per-row element count array (length `outerLen`).
    ///
    /// @return the sizes array
    public Array sizes() {
        return sizes;
    }

    /// Returns the child array at position `i` (0 = elements, 1 = offsets, 2 = sizes).
    ///
    /// @param i child index
    /// @return the child [Array]
    public Array child(int i) {
        return switch (i) {
            case 0 -> elements;
            case 1 -> offsets;
            case 2 -> sizes;
            default -> throw new VortexException("ListViewArray child index out of bounds: " + i);
        };
    }

    @Override
    public Array limited(long rows) {
        // offsets/sizes are per-row (length outerLen); cut both to `rows`. elements
        // stay shared — list-view rows can point anywhere, so nothing is trimmed.
        return new ListViewArray(dtype, rows, elements,
                Array.limited(offsets, rows), Array.limited(sizes, rows));
    }

    /// Always throws: a list-view array is offsets, sizes, and a flat elements child,
    /// not a single primary segment. Materialise [#elements()], [#offsets()], and
    /// [#sizes()] separately.
    ///
    /// @param arena unused
    /// @return never returns
    /// @throws VortexException always
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        throw new VortexException("ListViewArray has no primary segment");
    }
}
