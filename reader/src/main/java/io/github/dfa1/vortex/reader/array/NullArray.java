package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Concrete [Array] for all-null columns (`DType.Null`). Holds only a row count.
///
/// @param dtype  the null dtype
/// @param length number of null elements
public record NullArray(DType dtype, long length) implements Array {

    @Override
    public Array limited(long rows) {
        return new NullArray(dtype, rows);
    }

    /// Always throws: an all-null column holds only a row count, with no data buffer.
    ///
    /// @param arena unused
    /// @return never returns
    /// @throws VortexException always
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        throw new VortexException("NullArray has no primary segment");
    }
}
