package io.github.dfa1.vortex.reader.compute;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static io.github.dfa1.vortex.core.io.PTypeIO.LE_LONG;

/// Word-level helpers for the LSB-first validity bitmaps the compute kernels build, shared by
/// [Mask.BitmapMask] (popcount, partial-word, single-bit read) and [PrimitiveFilter] (word-wise
/// `AND` / `OR` of two bitmaps).
///
/// Every operation works on a [MemorySegment] of validity bits where position `i` lives in bit
/// `i & 7` of byte `i >>> 3`, little-endian across bytes — the same convention the decoded arrays
/// use. The 8-byte-word operations process full [Long] words and fall back to a per-byte tail for the
/// trailing partial word, so they stay safe for any bit count without reading past the segment.
///
/// These are per-word (or single-position) helpers, never the per-element hot loops: the kernels'
/// vectorizable value loops keep their own inlined bit writes (the hot-loop rule), and only the
/// once-per-node word combines and the bitmap bookkeeping route through here.
final class Bits {

    private Bits() {
    }

    /// Reads the bit at position `i` (LSB-first within each byte).
    ///
    /// @param bits the validity bitmap
    /// @param i    the zero-based position
    /// @return `true` if the bit at `i` is set
    static boolean get(MemorySegment bits, long i) {
        int b = bits.get(ValueLayout.JAVA_BYTE, i >>> 3) & 0xff;
        return (b & (1 << (i & 7))) != 0;
    }

    /// Sets the bit at position `i` (LSB-first within each byte).
    ///
    /// @param bits the validity bitmap
    /// @param i    the zero-based position
    static void set(MemorySegment bits, long i) {
        long byteIndex = i >>> 3;
        int existing = bits.get(ValueLayout.JAVA_BYTE, byteIndex) & 0xff;
        bits.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) (existing | (1 << (i & 7))));
    }

    /// Ands `src` into `dst` word-wise (`dst &= src`), processing full 8-byte words then a per-byte
    /// tail; padding bits past the row count stay zero because every producer only sets positions in
    /// `[0, n)`.
    ///
    /// @param dst the destination bitmap, mutated in place
    /// @param src the source bitmap
    /// @param n   the row count the bitmaps cover
    static void andInto(MemorySegment dst, MemorySegment src, long n) {
        long bytes = (n + 7) >>> 3;
        long words = bytes >>> 3;
        for (long w = 0; w < words; w++) {
            long off = w << 3;
            dst.set(LE_LONG, off, dst.get(LE_LONG, off) & src.get(LE_LONG, off));
        }
        for (long b = words << 3; b < bytes; b++) {
            dst.set(ValueLayout.JAVA_BYTE, b,
                    (byte) (dst.get(ValueLayout.JAVA_BYTE, b) & src.get(ValueLayout.JAVA_BYTE, b)));
        }
    }

    /// Ors `src` into `dst` word-wise (`dst |= src`), processing full 8-byte words then a per-byte
    /// tail.
    ///
    /// @param dst the destination bitmap, mutated in place
    /// @param src the source bitmap
    /// @param n   the row count the bitmaps cover
    static void orInto(MemorySegment dst, MemorySegment src, long n) {
        long bytes = (n + 7) >>> 3;
        long words = bytes >>> 3;
        for (long w = 0; w < words; w++) {
            long off = w << 3;
            dst.set(LE_LONG, off, dst.get(LE_LONG, off) | src.get(LE_LONG, off));
        }
        for (long b = words << 3; b < bytes; b++) {
            dst.set(ValueLayout.JAVA_BYTE, b,
                    (byte) (dst.get(ValueLayout.JAVA_BYTE, b) | src.get(ValueLayout.JAVA_BYTE, b)));
        }
    }

    /// Counts the set bits in the first `length` positions, reading full 8-byte words with
    /// [Long#bitCount(long)] and masking off any trailing garbage bits in the final partial word so
    /// they cannot inflate the count.
    ///
    /// @param bits   the validity bitmap
    /// @param length the number of positions to count
    /// @return the number of set bits in `[0, length)`
    static long popcount(MemorySegment bits, long length) {
        long fullWords = length >>> 6;
        long count = 0L;
        for (long w = 0; w < fullWords; w++) {
            count += Long.bitCount(bits.get(LE_LONG, w << 3));
        }
        int remaining = (int) (length & 63);
        if (remaining != 0) {
            count += Long.bitCount(partialWord(bits, fullWords, remaining));
        }
        return count;
    }

    /// Assembles the final partial word from the available tail bytes, masking it to the
    /// `remainingBits` live low bits so padding past the row count is cleared.
    ///
    /// @param bits         the validity bitmap
    /// @param wordIndex    the index of the partial word (number of preceding full words)
    /// @param remainingBits the count of live bits in this word, in `[1, 63]`
    /// @return the assembled word with only the live bits retained
    static long partialWord(MemorySegment bits, long wordIndex, int remainingBits) {
        long byteBase = wordIndex << 3;
        int availBytes = (remainingBits + 7) >>> 3;
        long w = 0L;
        for (int b = 0; b < availBytes; b++) {
            w |= (bits.get(ValueLayout.JAVA_BYTE, byteBase + b) & 0xffL) << (b << 3);
        }
        return w & ((1L << remainingBits) - 1);
    }
}
