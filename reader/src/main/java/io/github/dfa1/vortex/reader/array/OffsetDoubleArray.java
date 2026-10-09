package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

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

    /// Zero-copy over a flat inner buffer (see `OffsetArrays#window`), else the per-row default.
    ///
    /// @param arena allocator for the per-row fallback
    /// @return the window's little-endian values
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        return OffsetArrays.window(inner, offset, length, 8).orElseGet(() -> DoubleArray.super.materialize(arena));
    }
}
