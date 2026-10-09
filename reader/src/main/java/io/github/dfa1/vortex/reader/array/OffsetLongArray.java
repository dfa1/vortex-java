package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Sliced view over a [LongArray]: `getLong(i) = inner.getLong(i + offset)`.
///
/// Used by the scan iterator to expose a column that was decoded once (because
/// it shares a single flat layout across multiple aligned chunks) as a
/// per-chunk slice without copying. The wrapped `inner` retains the original
/// arena lifetime; this record adds no buffer of its own.
///
/// @param dtype  logical element type
/// @param length number of logical elements in this slice
/// @param inner  underlying long array (length must be `>= offset + length`)
/// @param offset starting index into `inner`
public record OffsetLongArray(DType dtype, long length, LongArray inner, long offset)
        implements LongArray {

    @Override
    public long getLong(long i) {
        return inner.getLong(i + offset);
    }

    /// Zero-copy over a flat inner buffer (see `OffsetArrays#window`), else the per-row default.
    ///
    /// @param arena allocator for the per-row fallback
    /// @return the window's little-endian values
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        return OffsetArrays.window(inner, offset, length, 8).orElseGet(() -> LongArray.super.materialize(arena));
    }
}
