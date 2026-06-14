package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// Lazy [IntArray] backed by the {@code vortex.zigzag} encoded {@code u32} child segment.
///
/// Decode is deferred to element access: {@code getInt(i) = (u >>> 1) ^ -(u & 1)}.
/// Returned by {@link io.github.dfa1.vortex.reader.decode.ZigZagEncodingDecoder} when the source
/// is not a broadcast constant.
///
/// @param dtype   logical I32 type
/// @param length  number of logical elements
/// @param encoded backing {@code u32} segment (one int per row, zigzag bit pattern)
/// @param arena   chunk-scoped allocator used for on-demand materialisation
public record LazyZigZagIntArray(DType dtype, long length, MemorySegment encoded,
                                 SegmentAllocator arena)
        implements IntArray {

    @Override
    public int getInt(long i) {
        int u = encoded.getAtIndex(PTypeIO.LE_INT, i);
        return (u >>> 1) ^ -(u & 1);
    }

    @Override
    public void forEachInt(IntConsumer c) {
        MemorySegment src = encoded;
        long n = length;
        for (long i = 0; i < n; i++) {
            int u = src.getAtIndex(PTypeIO.LE_INT, i);
            c.accept((u >>> 1) ^ -(u & 1));
        }
    }

    @Override
    public int fold(int identity, IntBinaryOperator op) {
        MemorySegment src = encoded;
        long n = length;
        int result = identity;
        for (long i = 0; i < n; i++) {
            int u = src.getAtIndex(PTypeIO.LE_INT, i);
            result = op.applyAsInt(result, (u >>> 1) ^ -(u & 1));
        }
        return result;
    }
}
