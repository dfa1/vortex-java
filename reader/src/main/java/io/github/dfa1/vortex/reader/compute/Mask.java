package io.github.dfa1.vortex.reader.compute;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

import static io.github.dfa1.vortex.core.io.PTypeIO.LE_LONG;

/// A positional selection over a fixed number of rows, the foundation for the compute kernels
/// sketched in ADR 0013 §1.
///
/// A mask answers, for each logical position in `[0, length())`, whether that row is selected.
/// Kernels intersect masks across pipeline stages and downstream stages honour them (skip
/// excluded positions during a reduce, emit a smaller result for a take) so that nothing
/// materialises until a sink demands it.
///
/// The four variants trade representation for the producer that creates them:
/// - [Mask.AllTrue] / [Mask.AllFalse] — every position selected / rejected, allocation-free.
/// - [Mask.RangeMask] — a single contiguous run, produced by `LIMIT` / `SLICE`.
/// - [Mask.BitmapMask] — an arbitrary bit set viewed over a [MemorySegment], produced by the
///   `compare` / `between` / `is_null` kernels.
///
/// All variants are read-only views: a mask never copies or allocates a bitmap of its own.
public sealed interface Mask permits Mask.AllTrue, Mask.AllFalse, Mask.RangeMask, Mask.BitmapMask {

    /// Returns the number of positions this mask covers.
    ///
    /// @return the row count, always `>= 0`
    long length();

    /// Returns how many of the covered positions are selected.
    ///
    /// @return the count of selected rows, in `[0, length()]`
    long trueCount();

    /// Reports whether the position at `i` is selected.
    ///
    /// @param i zero-based position, must be in `[0, length())`
    /// @return `true` if the row at `i` is selected
    /// @throws IndexOutOfBoundsException if `i` is outside `[0, length())`
    boolean get(long i);

    /// Creates a mask that selects every one of `length` positions.
    ///
    /// @param length the row count, must be `>= 0`
    /// @return an all-selected mask of the given length
    /// @throws IllegalArgumentException if `length` is negative
    static Mask allTrue(long length) {
        return new AllTrue(length);
    }

    /// Creates a mask that selects none of `length` positions.
    ///
    /// @param length the row count, must be `>= 0`
    /// @return an all-rejected mask of the given length
    /// @throws IllegalArgumentException if `length` is negative
    static Mask allFalse(long length) {
        return new AllFalse(length);
    }

    /// A mask whose every position is selected.
    ///
    /// Carries only the length: there is one logical "all true" mask per size, so there is nothing
    /// else to store. The ADR calls these singletons; length still varies, so each size is a
    /// distinct (cheap) instance built through [Mask#allTrue(long)].
    ///
    /// @param length the row count, must be `>= 0`
    record AllTrue(long length) implements Mask {

        /// Validates the length.
        ///
        /// @param length the row count, must be `>= 0`
        public AllTrue {
            requireNonNegativeLength(length);
        }

        @Override
        public long trueCount() {
            return length;
        }

        @Override
        public boolean get(long i) {
            Objects.checkIndex(i, length);
            return true;
        }
    }

    /// A mask whose every position is rejected.
    ///
    /// The all-false counterpart of [Mask.AllTrue]; carries only the length and is built through
    /// [Mask#allFalse(long)].
    ///
    /// @param length the row count, must be `>= 0`
    record AllFalse(long length) implements Mask {

        /// Validates the length.
        ///
        /// @param length the row count, must be `>= 0`
        public AllFalse {
            requireNonNegativeLength(length);
        }

        @Override
        public long trueCount() {
            return 0L;
        }

        @Override
        public boolean get(long i) {
            Objects.checkIndex(i, length);
            return false;
        }
    }

    /// A mask that selects the contiguous half-open range `[start, end)` within `[0, length)`.
    ///
    /// Produced by positional operators such as `LIMIT` and `SLICE`, where the selection is a
    /// single run with no gaps. An empty range (`start == end`) selects nothing.
    ///
    /// @param start the first selected position, must satisfy `0 <= start <= end`
    /// @param end   the position just past the last selected one, must satisfy `start <= end <= length`
    /// @param length the row count, must be `>= 0`
    record RangeMask(long start, long end, long length) implements Mask {

        /// Validates that the range sits within the covered length.
        ///
        /// @param start  the first selected position, must satisfy `0 <= start <= end`
        /// @param end    the exclusive upper bound, must satisfy `start <= end <= length`
        /// @param length the row count, must be `>= 0`
        public RangeMask {
            requireNonNegativeLength(length);
            if (start < 0 || start > end || end > length) {
                throw new IllegalArgumentException(
                        "range [" + start + ", " + end + ") out of bounds for length " + length);
            }
        }

        @Override
        public long trueCount() {
            return end - start;
        }

        @Override
        public boolean get(long i) {
            Objects.checkIndex(i, length);
            return i >= start && i < end;
        }
    }

    /// A mask backed by a [MemorySegment] of validity bits, one bit per position.
    ///
    /// Bits are LSB-first within each byte, little-endian across bytes — the position `i` lives in
    /// bit `i & 7` of byte `i >>> 3`, matching the validity-bitmap convention used by the decoded
    /// arrays (see `MaterializedBoolArray#getBoolean(long)`). The selected count is computed once at
    /// construction so [#trueCount()] is constant-time.
    ///
    /// The segment is viewed, never copied: its lifetime is the caller's responsibility (typically
    /// the reader's confined arena).
    final class BitmapMask implements Mask {

        private final MemorySegment bits;
        private final long length;
        private final long trueCount;

        /// Wraps a validity-bit segment as a mask, counting the set bits once.
        ///
        /// @param bits   the LSB-first validity bitmap; must hold at least `(length + 7) / 8` bytes
        /// @param length the row count, must be `>= 0`
        /// @throws IllegalArgumentException if `length` is negative or `bits` is too small to cover it
        public BitmapMask(MemorySegment bits, long length) {
            Objects.requireNonNull(bits, "bits");
            requireNonNegativeLength(length);
            long required = (length + 7) >>> 3;
            if (bits.byteSize() < required) {
                throw new IllegalArgumentException(
                        "bitmap of " + bits.byteSize() + " bytes too small for length " + length);
            }
            this.bits = bits;
            this.length = length;
            this.trueCount = popcount(bits, length);
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public long trueCount() {
            return trueCount;
        }

        @Override
        public boolean get(long i) {
            Objects.checkIndex(i, length);
            int b = bits.get(ValueLayout.JAVA_BYTE, i >>> 3) & 0xff;
            return (b & (1 << (i & 7))) != 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BitmapMask other) || length != other.length) {
                return false;
            }
            long fullWords = length >>> 6;
            for (long w = 0; w < fullWords; w++) {
                if (bits.get(LE_LONG, w << 3) != other.bits.get(LE_LONG, w << 3)) {
                    return false;
                }
            }
            int remaining = (int) (length & 63);
            return remaining == 0
                    || partialWord(bits, fullWords, remaining) == partialWord(other.bits, fullWords, remaining);
        }

        @Override
        public int hashCode() {
            long h = length;
            long fullWords = length >>> 6;
            for (long w = 0; w < fullWords; w++) {
                h = 31 * h + bits.get(LE_LONG, w << 3);
            }
            int remaining = (int) (length & 63);
            if (remaining != 0) {
                h = 31 * h + partialWord(bits, fullWords, remaining);
            }
            return Long.hashCode(h);
        }

        @Override
        public String toString() {
            return "BitmapMask[length=" + length + ", trueCount=" + trueCount + "]";
        }

        /// Counts the set bits in the first `length` positions, reading full 8-byte words with
        /// [Long#bitCount(long)] and masking off any trailing garbage bits in the final partial
        /// word so they cannot inflate the count.
        ///
        /// @param bits   the validity bitmap
        /// @param length the number of positions to count
        /// @return the number of set bits in `[0, length)`
        private static long popcount(MemorySegment bits, long length) {
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
        /// `remainingBits` live low bits so padding past `length` is cleared.
        ///
        /// @param bits         the validity bitmap
        /// @param wordIndex    the index of the partial word (number of preceding full words)
        /// @param remainingBits the count of live bits in this word, in `[1, 63]`
        /// @return the assembled word with only the live bits retained
        private static long partialWord(MemorySegment bits, long wordIndex, int remainingBits) {
            long byteBase = wordIndex << 3;
            int availBytes = (remainingBits + 7) >>> 3;
            long w = 0L;
            for (int b = 0; b < availBytes; b++) {
                w |= (bits.get(ValueLayout.JAVA_BYTE, byteBase + b) & 0xffL) << (b << 3);
            }
            return w & ((1L << remainingBits) - 1);
        }
    }

    /// Rejects a negative length with a uniform message, shared by every variant's constructor.
    ///
    /// @param length the proposed row count
    /// @throws IllegalArgumentException if `length` is negative
    private static void requireNonNegativeLength(long length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0, got " + length);
        }
    }
}
