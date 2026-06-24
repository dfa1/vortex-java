package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

/// Sliced view over a [BoolArray]: `getBoolean(i) = inner.getBoolean(i + offset)`.
///
/// @param dtype  logical element type
/// @param length number of logical elements in this slice
/// @param inner  underlying bool array
/// @param offset starting index into `inner`
public record OffsetBoolArray(DType dtype, long length, BoolArray inner, long offset)
        implements BoolArray {

    @Override
    public boolean getBoolean(long i) {
        return inner.getBoolean(i + offset);
    }
}
