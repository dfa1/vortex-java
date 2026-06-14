package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;

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
public record LazyForLongArray(DType dtype, long length, MemorySegment encoded, long ref)
        implements LongArray {

    @Override
    public long getLong(long i) {
        return encoded.getAtIndex(PTypeIO.LE_LONG, i) + ref;
    }
}
