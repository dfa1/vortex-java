package io.github.dfa1.vortex.reader.array;


import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;

/// [Array] for bit-packed boolean columns (LSB-first, one byte per 8 elements).
///
/// The default impl is [MaterializedBoolArray], a buffer-backed record
/// returned when an encoding decoder either materialises values eagerly or
/// has no lazy variant of its own.
public non-sealed interface BoolArray extends Array {

    /// Returns the boolean value at the given logical index.
    ///
    /// @param i zero-based logical index (must be in `[0, length())`)
    /// @return the boolean value at position `i`
    boolean getBoolean(long i);

    /// Passes each boolean element to the given consumer in order.
    ///
    /// @param c consumer that receives each boolean element
    default void forEachBoolean(BooleanConsumer c) {
        long n = length();
        for (long i = 0; i < n; i++) {
            c.accept(getBoolean(i));
        }
    }

    /// Zero-copy truncation: a length-capping view over this array.
    ///
    /// @param rows number of leading elements to keep
    /// @return a length-`rows` bool array view
    @Override
    default Array limited(long rows) {
        return new OffsetBoolArray(dtype(), rows, this, 0);
    }

    /// Scalar fallback: packs every element through [#getBoolean(long)] into a fresh
    /// LSB-first bitmap (one byte per 8 elements), matching the on-disk and Arrow
    /// validity-buffer layout. Buffer-backed ([MaterializedBoolArray]) overrides with
    /// a zero-copy path. The segment is allocated zero-filled, so only set bits are
    /// written.
    ///
    /// @param arena allocator for the output segment
    /// @return an LSB-first packed bitmap covering `length()` elements
    @Override
    default MemorySegment materialize(SegmentAllocator arena) {
        long n = length();
        MemorySegment dst = arena.allocate((n + 7) / 8);
        for (long i = 0; i < n; i++) {
            if (getBoolean(i)) {
                long byteIndex = i >>> 3;
                byte b = dst.get(ValueLayout.JAVA_BYTE, byteIndex);
                dst.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) (b | (1 << (i & 7))));
            }
        }
        return dst;
    }
}
