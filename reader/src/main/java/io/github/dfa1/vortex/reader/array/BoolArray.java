package io.github.dfa1.vortex.reader.array;


import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;

/// [Array] for bit-packed boolean columns (LSB-first, one byte per 8 elements).
///
/// The default impl is [MaterializedBoolArray], a buffer-backed record
/// returned when an encoding decoder either materializes values eagerly or
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
    /// a zero-copy path. Walks [#forEachBoolean(BooleanConsumer)], so run-end, sparse and chunked
    /// bools emit sequentially rather than resolving each row from scratch, and assembles each
    /// output byte in a register, stored once, rather than read-modified-written per set bit.
    ///
    /// @param arena allocator for the output segment
    /// @return an LSB-first packed bitmap covering `length()` elements
    @Override
    default MemorySegment materialize(SegmentAllocator arena) {
        long n = length();
        MemorySegment dst = arena.allocate((n + 7) / 8);
        long[] state = {0, 0}; // [row, byte being assembled]
        forEachBoolean(v -> {
            long i = state[0]++;
            if (i >= n) {
                return; // a walk emitting more than length() rows must not write past the bitmap
            }
            if (v) {
                state[1] |= 1L << (i & 7);
            }
            if ((i & 7) == 7) {
                dst.set(ValueLayout.JAVA_BYTE, i >>> 3, (byte) state[1]);
                state[1] = 0;
            }
        });
        if ((n & 7) != 0) {
            dst.set(ValueLayout.JAVA_BYTE, n >>> 3, (byte) state[1]);
        }
        return dst;
    }
}
