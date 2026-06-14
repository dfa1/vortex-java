package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Lazy [LongArray] backed by the {@code fastlanes.for} encoded {@code i64} child segment.
///
/// Decode is deferred to element access: {@code getLong(i) = encoded[i] + ref}.
/// Returned by {@link io.github.dfa1.vortex.reader.decode.FrameOfReferenceEncodingDecoder} when
/// {@code ref != 0} and the source is not a broadcast constant.
///
/// @param dtype   logical I64/U64 type
/// @param length  number of logical elements
/// @param encoded backing {@code i64} segment (one long per row)
/// @param ref     reference value added to each encoded element
/// @param arena   chunk-scoped allocator used for on-demand materialisation
public record LazyForLongArray(DType dtype, long length, MemorySegment encoded, long ref,
                               SegmentAllocator arena)
        implements LongArray {

    @Override
    public long getLong(long i) {
        return encoded.getAtIndex(PTypeIO.LE_LONG, i) + ref;
    }

    @Override
    public void forEachLong(LongConsumer c) {
        MemorySegment src = encoded;
        long r = ref;
        long n = length;
        for (long i = 0; i < n; i++) {
            c.accept(src.getAtIndex(PTypeIO.LE_LONG, i) + r);
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        MemorySegment src = encoded;
        long r = ref;
        long n = length;
        long result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsLong(result, src.getAtIndex(PTypeIO.LE_LONG, i) + r);
        }
        return result;
    }
}
