package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;

/// Lazy [LongArray] backed by the {@code vortex.zigzag} encoded {@code u64} child segment.
///
/// Decode is deferred to element access: {@code getLong(i) = (u >>> 1) ^ -(u & 1)}.
/// Returned by {@link io.github.dfa1.vortex.reader.decode.ZigZagEncodingDecoder} when the source
/// is not a broadcast constant.
///
/// @param dtype   logical I64 type
/// @param length  number of logical elements
/// @param encoded backing {@code u64} segment (one long per row, zigzag bit pattern)
public record LazyZigZagLongArray(DType dtype, long length, MemorySegment encoded)
        implements LongArray {

    @Override
    public long getLong(long i) {
        long u = encoded.getAtIndex(PTypeIO.LE_LONG, i);
        return (u >>> 1) ^ -(u & 1L);
    }
}
