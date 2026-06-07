package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

/// Decoded variable-length list array.
///
/// <p>List {@code i} covers {@code elements[offsets[i]..offsets[i+1])}.
/// {@code offsets} has length {@code outerLen + 1}.
public final class ListArray implements Array {

    private final DType.List dtype;
    private final long outerLen;
    private final Array elements;
    private final Array offsets;

    /// Creates a new {@code ListArray}.
    ///
    /// @param dtype    logical list type
    /// @param outerLen number of outer list elements
    /// @param elements flat elements array (length = total element count)
    /// @param offsets  offsets array of length {@code outerLen + 1}
    public ListArray(DType.List dtype, long outerLen, Array elements, Array offsets) {
        this.dtype = dtype;
        this.outerLen = outerLen;
        this.elements = elements;
        this.offsets = offsets;
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

    /// Returns the offsets array (length {@code outerLen + 1}).
    ///
    /// @return the offsets array
    public Array offsets() {
        return offsets;
    }

    /// Returns the child array at position {@code i} (0 = elements, 1 = offsets).
    ///
    /// @param i child index
    /// @return the child {@link Array}
    public Array child(int i) {
        return switch (i) {
            case 0 -> elements;
            case 1 -> offsets;
            default -> throw new VortexException("ListArray child index out of bounds: " + i);
        };
    }
}
