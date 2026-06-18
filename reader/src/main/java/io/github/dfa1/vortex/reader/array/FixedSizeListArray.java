package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

/// Decoded fixed-size list array: holds a flat elements [Array] of length `outerLen * fixedSize`.
///
/// List `i` covers elements `[i*fixedSize, (i+1)*fixedSize)`.
public final class FixedSizeListArray implements Array {

    private final DType.FixedSizeList dtype;
    private final long outerLen;
    private final Array elements;

    /// Constructs a `FixedSizeListArray` from a flat elements array.
    ///
    /// @param dtype    logical fixed-size list type (provides element type and fixed size)
    /// @param outerLen number of outer list elements
    /// @param elements flat array of `outerLen * fixedSize` element values
    public FixedSizeListArray(DType.FixedSizeList dtype, long outerLen, Array elements) {
        this.dtype = dtype;
        this.outerLen = outerLen;
        this.elements = elements;
    }

    @Override
    public long length() {
        return outerLen;
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    /// Returns the flat elements array containing `outerLen * fixedSize` values.
    ///
    /// @return the flat elements array
    public Array elements() {
        return elements;
    }

    /// Returns the fixed number of elements per outer list entry.
    ///
    /// @return the fixed size from the dtype
    public int fixedSize() {
        return dtype.fixedSize();
    }

    /// Returns the elements child array.
    ///
    /// @param i must be 0
    /// @return the child {@link Array} at index `i`
    public Array child(int i) {
        if (i != 0) {
            throw new ArrayIndexOutOfBoundsException("FixedSizeListArray child index " + i);
        }
        return elements;
    }

    @Override
    public Array limited(long rows) {
        // Each outer row spans `fixedSize` contiguous elements, so the first `rows`
        // rows are the first `rows * fixedSize` elements.
        return new FixedSizeListArray(dtype, rows, Array.limited(elements, rows * fixedSize()));
    }
}
