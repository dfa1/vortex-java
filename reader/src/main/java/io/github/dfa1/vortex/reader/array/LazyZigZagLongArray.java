package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Lazy [LongArray] backed by the `vortex.zigzag` encoded `u64` child segment.
///
/// Decode is deferred to element access: `getLong(i) = (u >>> 1) ^ -(u & 1)`.
/// Returned by [io.github.dfa1.vortex.reader.decode.ZigZagEncodingDecoder] when the source
/// is not a broadcast constant.
///
/// @param dtype   logical I64 type
/// @param length  number of logical elements
/// @param encoded backing `u64` segment (one long per row, zigzag bit pattern)
public record LazyZigZagLongArray(DType dtype, long length, MemorySegment encoded)
        implements LongArray {

    @Override
    public long getLong(long i) {
        long u = encoded.getAtIndex(PTypeIO.LE_LONG, i);
        return (u >>> 1) ^ -(u & 1L);
    }

    /// Bulk-decodes through [#getLong(long)] into a fresh little-endian `i64` segment.
    /// The decode formula lives only in [#getLong(long)]; this override exists solely to
    /// give the JIT a monomorphic, inlinable call site (the shared [LongArray] default is
    /// megamorphic across every implementation and will not inline or auto-vectorise).
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `i64` segment of decoded values
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n * 8L, 8);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_LONG, i, getLong(i));
        }
        return dst;
    }
}
