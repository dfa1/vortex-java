package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

/// Decoded variable-length list-view array (Arrow ListView layout).
///
/// <p>Unlike {@link ListArray}, offsets and sizes are independent per row:
/// {@code list[i] = elements[offsets[i] .. offsets[i] + sizes[i]]}.
/// Both {@code offsets} and {@code sizes} have length {@code outerLen} (not outerLen+1).
public final class ListViewArray implements Array {

    private final DType.List dtype;
    private final long outerLen;
    private final Array elements;
    private final Array offsets;
    private final Array sizes;

    /// Creates a new {@code ListViewArray}.
    ///
    /// @param dtype    logical list type
    /// @param outerLen number of outer list elements
    /// @param elements flat elements array
    /// @param offsets  per-row start offsets into {@code elements} (length {@code outerLen})
    /// @param sizes    per-row element counts (length {@code outerLen})
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

    /// Returns the per-row start offsets array (length {@code outerLen}).
    ///
    /// @return the offsets array
    public Array offsets() {
        return offsets;
    }

    /// Returns the per-row element count array (length {@code outerLen}).
    ///
    /// @return the sizes array
    public Array sizes() {
        return sizes;
    }

    /// Returns the child array at position {@code i} (0 = elements, 1 = offsets, 2 = sizes).
    ///
    /// @param i child index
    /// @return the child {@link Array}
    public Array child(int i) {
        return switch (i) {
            case 0 -> elements;
            case 1 -> offsets;
            case 2 -> sizes;
            default -> throw new VortexException("ListViewArray child index out of bounds: " + i);
        };
    }
}
