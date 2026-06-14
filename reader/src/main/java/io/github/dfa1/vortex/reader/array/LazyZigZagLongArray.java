package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Lazy [LongArray] backed by the {@code vortex.zigzag} encoded {@code u64} child segment.
///
/// Decode is deferred to element access: {@code getLong(i) = (u >>> 1) ^ -(u & 1)}.
/// Returned by {@link io.github.dfa1.vortex.reader.decode.ZigZagEncodingDecoder} when the source
/// is not a broadcast constant.
///
/// @param dtype   logical I64 type
/// @param length  number of logical elements
/// @param encoded backing {@code u64} segment (one long per row, zigzag bit pattern)
/// @param arena   chunk-scoped allocator used for on-demand materialisation
public record LazyZigZagLongArray(DType dtype, long length, MemorySegment encoded,
                                  SegmentAllocator arena)
        implements LongArray {

    @Override
    public long getLong(long i) {
        long u = encoded.getAtIndex(PTypeIO.LE_LONG, i);
        return (u >>> 1) ^ -(u & 1L);
    }

    @Override
    public void forEachLong(LongConsumer c) {
        MemorySegment src = encoded;
        long n = length;
        for (long i = 0; i < n; i++) {
            long u = src.getAtIndex(PTypeIO.LE_LONG, i);
            c.accept((u >>> 1) ^ -(u & 1L));
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        MemorySegment src = encoded;
        long n = length;
        long result = identity;
        for (long i = 0; i < n; i++) {
            long u = src.getAtIndex(PTypeIO.LE_LONG, i);
            result = op.applyAsLong(result, (u >>> 1) ^ -(u & 1L));
        }
        return result;
    }
}
