package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

/// Sliced view over a [DoubleArray]: `getDouble(i) = inner.getDouble(i + offset)`.
///
/// @param dtype  logical element type
/// @param length number of logical elements in this slice
/// @param inner  underlying double array
/// @param offset starting index into `inner`
public record OffsetDoubleArray(DType dtype, long length, DoubleArray inner, long offset)
        implements DoubleArray {

    @Override
    public double getDouble(long i) {
        return inner.getDouble(i + offset);
    }
}
