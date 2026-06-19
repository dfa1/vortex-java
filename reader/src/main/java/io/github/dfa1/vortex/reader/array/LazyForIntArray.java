package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Lazy [IntArray] backed by the `fastlanes.for` encoded `i32` child segment.
///
/// Decode is deferred to element access: `getInt(i) = encoded[i] + ref`.
/// Returned by [io.github.dfa1.vortex.reader.decode.FrameOfReferenceEncodingDecoder] when
/// `ref != 0` and the source is not a broadcast constant.
///
/// @param dtype   logical I32/U32 type
/// @param length  number of logical elements
/// @param encoded backing `i32` segment (one int per row)
/// @param ref     reference value added to each encoded element
public record LazyForIntArray(DType dtype, long length, MemorySegment encoded, int ref)
        implements IntArray {

    @Override
    public int getInt(long i) {
        return encoded.getAtIndex(PTypeIO.LE_INT, i) + ref;
    }

    /// Bulk-decodes through [#getInt(long)] into a fresh little-endian `i32` segment.
    /// The decode formula lives only in [#getInt(long)]; this override exists solely to
    /// give the JIT a monomorphic, inlinable call site (the shared [IntArray] default is
    /// megamorphic across every implementation and will not inline or auto-vectorise).
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `i32` segment of decoded values
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n * 4L, 4);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_INT, i, getInt(i));
        }
        return dst;
    }
}
