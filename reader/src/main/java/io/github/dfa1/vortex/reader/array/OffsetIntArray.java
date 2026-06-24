package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

/// Sliced view over an [IntArray]: `getInt(i) = inner.getInt(i + offset)`.
///
/// @param dtype  logical element type
/// @param length number of logical elements in this slice
/// @param inner  underlying int array
/// @param offset starting index into `inner`
public record OffsetIntArray(DType dtype, long length, IntArray inner, long offset)
        implements IntArray {

    @Override
    public int getInt(long i) {
        return inner.getInt(i + offset);
    }
}
