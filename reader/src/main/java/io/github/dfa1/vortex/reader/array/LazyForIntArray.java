package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// Lazy [IntArray] backed by the {@code fastlanes.for} encoded {@code i32} child segment.
///
/// Decode is deferred to element access: {@code getInt(i) = encoded[i] + ref}.
/// Returned by {@link io.github.dfa1.vortex.reader.decode.FrameOfReferenceEncodingDecoder} when
/// {@code ref != 0} and the source is not a broadcast constant.
///
/// @param dtype   logical I32/U32 type
/// @param length  number of logical elements
/// @param encoded backing {@code i32} segment (one int per row)
/// @param ref     reference value added to each encoded element
/// @param arena   chunk-scoped allocator used for on-demand materialisation
public record LazyForIntArray(DType dtype, long length, MemorySegment encoded, int ref,
                              SegmentAllocator arena)
        implements IntArray {

    @Override
    public int getInt(long i) {
        return encoded.getAtIndex(PTypeIO.LE_INT, i) + ref;
    }

    @Override
    public void forEachInt(IntConsumer c) {
        MemorySegment src = encoded;
        int r = ref;
        long n = length;
        for (long i = 0; i < n; i++) {
            c.accept(src.getAtIndex(PTypeIO.LE_INT, i) + r);
        }
    }

    @Override
    public int fold(int identity, IntBinaryOperator op) {
        MemorySegment src = encoded;
        int r = ref;
        long n = length;
        int result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsInt(result, src.getAtIndex(PTypeIO.LE_INT, i) + r);
        }
        return result;
    }
}
