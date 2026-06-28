package io.github.dfa1.vortex.reader.compute;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Objects;

import static io.github.dfa1.vortex.core.io.PTypeIO.LE_LONG;

/// A positional selection over a fixed number of rows, the foundation for the compute kernels
/// sketched in ADR 0013 §1.
///
/// A mask answers, for each logical position in `[0, length())`, whether that row is selected.
/// Kernels intersect masks across pipeline stages and downstream stages honor them (skip
/// excluded positions during a reduce, emit a smaller result for a take) so that nothing
/// materializes until a sink demands it.
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

    /// Intersects this mask with `other`, selecting a position only when **both** masks select it
    /// (logical `AND`). Both masks must cover the same number of positions.
    ///
    /// The all-selected / all-rejected operands short-circuit allocation-free — `allFalse AND x` is
    /// `allFalse`, `allTrue AND x` is `x` — so a fresh bitmap is built from `allocator` only when both
    /// operands carry per-position bits. When both are [Mask.BitmapMask] the combine is word-wise; a
    /// (rare) range operand falls back to a per-position combine.
    ///
    /// @param other     the mask to intersect with, of the same length as this one
    /// @param allocator the allocator for the result bitmap when one must be built (its zero-fill
    ///                  seeds the rejected bits)
    /// @return a mask selecting the positions both masks select
    /// @throws IllegalArgumentException if `other` covers a different number of positions
    default Mask and(Mask other, SegmentAllocator allocator) {
        Objects.requireNonNull(other, "other");
        Objects.requireNonNull(allocator, "allocator");
        requireSameLength(this, other);
        if (this instanceof AllFalse || other instanceof AllTrue) {
            return this;
        }
        if (other instanceof AllFalse || this instanceof AllTrue) {
            return other;
        }
        return BitmapMask.combine(this, other, allocator, true);
    }

    /// Unions this mask with `other`, selecting a position when **either** mask selects it (logical
    /// `OR`). Both masks must cover the same number of positions.
    ///
    /// The all-selected / all-rejected operands short-circuit allocation-free — `allTrue OR x` is
    /// `allTrue`, `allFalse OR x` is `x` — so a fresh bitmap is built from `allocator` only when both
    /// operands carry per-position bits.
    ///
    /// @param other     the mask to union with, of the same length as this one
    /// @param allocator the allocator for the result bitmap when one must be built
    /// @return a mask selecting the positions either mask selects
    /// @throws IllegalArgumentException if `other` covers a different number of positions
    default Mask or(Mask other, SegmentAllocator allocator) {
        Objects.requireNonNull(other, "other");
        Objects.requireNonNull(allocator, "allocator");
        requireSameLength(this, other);
        if (this instanceof AllTrue || other instanceof AllFalse) {
            return this;
        }
        if (other instanceof AllTrue || this instanceof AllFalse) {
            return other;
        }
        return BitmapMask.combine(this, other, allocator, false);
    }

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
            this.trueCount = Bits.popcount(bits, length);
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
            return Bits.get(bits, i);
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
                    || Bits.partialWord(bits, fullWords, remaining)
                            == Bits.partialWord(other.bits, fullWords, remaining);
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
                h = 31 * h + Bits.partialWord(bits, fullWords, remaining);
            }
            return Long.hashCode(h);
        }

        @Override
        public String toString() {
            return "BitmapMask[length=" + length + ", trueCount=" + trueCount + "]";
        }

        /// Combines two per-position masks into a fresh [BitmapMask] from `allocator`, ANDing or ORing
        /// them. When both are [BitmapMask] the combine is word-wise (via [Bits]); a range operand is
        /// handled by a per-position fallback. The reduced trivial operands never reach here — they are
        /// short-circuited in [Mask#and(Mask, SegmentAllocator)] / [Mask#or(Mask, SegmentAllocator)].
        ///
        /// @param a         the first operand, of the same length as `b`
        /// @param b         the second operand
        /// @param allocator the allocator for the result bitmap (its zero-fill seeds the cleared bits)
        /// @param and       `true` to AND the operands, `false` to OR them
        /// @return the combined mask
        static Mask combine(Mask a, Mask b, SegmentAllocator allocator, boolean and) {
            long n = a.length();
            MemorySegment out = allocator.allocate((n + 7) >>> 3);
            if (a instanceof BitmapMask ba && b instanceof BitmapMask bb) {
                MemorySegment.copy(ba.bits, 0, out, 0, (n + 7) >>> 3);
                if (and) {
                    Bits.andInto(out, bb.bits, n);
                } else {
                    Bits.orInto(out, bb.bits, n);
                }
            } else {
                for (long i = 0; i < n; i++) {
                    boolean selected = and ? (a.get(i) && b.get(i)) : (a.get(i) || b.get(i));
                    if (selected) {
                        Bits.set(out, i);
                    }
                }
            }
            return new BitmapMask(out, n);
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

    /// Rejects two masks of differing length, the precondition the [#and(Mask, SegmentAllocator)] and
    /// [#or(Mask, SegmentAllocator)] combinators share.
    ///
    /// @param a the first mask
    /// @param b the second mask
    /// @throws IllegalArgumentException if the masks cover a different number of positions
    private static void requireSameLength(Mask a, Mask b) {
        if (a.length() != b.length()) {
            throw new IllegalArgumentException(
                    "mask lengths differ: " + a.length() + " != " + b.length());
        }
    }
}
