package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

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

    /// Zero-copy over a flat inner buffer (see `OffsetArrays#window`), else the per-row default.
    ///
    /// @param arena allocator for the per-row fallback
    /// @return the window's little-endian values
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        return OffsetArrays.window(inner, offset, length, 4).orElseGet(() -> FloatArray.super.materialize(arena));
    }
}
