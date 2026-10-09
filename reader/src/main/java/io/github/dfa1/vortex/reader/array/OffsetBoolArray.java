package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;

/// Sliced view over a [BoolArray]: `getBoolean(i) = inner.getBoolean(i + offset)`.
///
/// @param dtype  logical element type
/// @param length number of logical elements in this slice
/// @param inner  underlying bool array
/// @param offset starting index into `inner`
public record OffsetBoolArray(DType dtype, long length, BoolArray inner, long offset)
        implements BoolArray {

    @Override
    public boolean getBoolean(long i) {
        return inner.getBoolean(i + offset);
    }

    /// Over a flat bitmap, copies the window 8 rows at a time, each output byte the two source
    /// bytes it straddles shifted together, instead of one `getBoolean` per row. A slice of a
    /// shared bool column lands here once per scan window.
    ///
    /// @param arena allocator for the output segment
    /// @return an LSB-first packed bitmap of this window, bits past `length()` cleared
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        if (!(inner instanceof MaterializedBoolArray flat)
                || ((offset + length + 7) >>> 3) > flat.materialize(arena).byteSize()) {
            return BoolArray.super.materialize(arena);
        }
        MemorySegment src = flat.materialize(arena);
        long outBytes = (length + 7) >>> 3;
        MemorySegment dst = arena.allocate(outBytes);
        long srcBytes = src.byteSize();
        long first = offset >>> 3;
        int shift = (int) (offset & 7);
        for (long k = 0; k < outBytes; k++) {
            long at = first + k;
            int lo = Byte.toUnsignedInt(src.get(ValueLayout.JAVA_BYTE, at)) >>> shift;
            int hi = shift != 0 && at + 1 < srcBytes ? Byte.toUnsignedInt(src.get(ValueLayout.JAVA_BYTE, at + 1)) << (8 - shift) : 0;
            dst.set(ValueLayout.JAVA_BYTE, k, (byte) (lo | hi));
        }
        int tail = (int) (length & 7);
        if (tail != 0) {
            byte last = dst.get(ValueLayout.JAVA_BYTE, outBytes - 1);
            dst.set(ValueLayout.JAVA_BYTE, outBytes - 1, (byte) (last & ((1 << tail) - 1)));
        }
        return dst;
    }
}
