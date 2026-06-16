package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

/// Sliced view over a [FloatArray]: `getFloat(i) = inner.getFloat(i + offset)`.
///
/// @param dtype  logical element type
/// @param length number of logical elements in this slice
/// @param inner  underlying float array
/// @param offset starting index into `inner`
public record OffsetFloatArray(DType dtype, long length, FloatArray inner, long offset)
        implements FloatArray {

    @Override
    public float getFloat(long i) {
        return inner.getFloat(i + offset);
    }
}
