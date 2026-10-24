package io.github.dfa1.vortex.reader.array;

import java.util.Arrays;

/// The rows of an array a filter keeps, as Rust's `vortex_mask::Mask`: every row, no row, or some
/// rows held as a bitmap. The bitmap derives its row indices and its runs of consecutive rows on
/// first use and keeps them, so each column a filtered scan copies reuses them instead of walking
/// the bitmap again.
public sealed interface Mask permits Mask.AllTrue, Mask.AllFalse, Mask.Values {

    /// Returns the number of rows the mask covers.
    ///
    /// @return the row count, kept or not
    int length();

    /// Returns the number of rows the mask keeps.
    ///
    /// @return the kept row count
    int trueCount();

    /// The mask keeping row `i` when bit `i` of `words` is set, as Rust's `Mask::from_buffer`: a
    /// bitmap keeping every row or none becomes [AllTrue] or [AllFalse].
    ///
    /// @param words  the bitmap, bit `i` for row `i`; it is taken over, not copied, and any bit past
    ///               `length` in its last word is cleared
    /// @param length the row count; `words` holds `(length + 63) / 64` words
    /// @return the mask
    /// @throws IllegalArgumentException if `words` does not hold `length` bits
    static Mask fromBitmap(long[] words, int length) {
        if (length < 0 || words.length != (length + 63) >>> 6) {
            throw new IllegalArgumentException(words.length + " words for " + length + " rows");
        }
        // a stray bit past the last row would count as a kept row that does not exist
        if ((length & 63) != 0) {
            words[words.length - 1] &= (1L << length) - 1;
        }
        int trueCount = 0;
        for (long word : words) {
            trueCount += Long.bitCount(word);
        }
        if (trueCount == 0) {
            return new AllFalse(length);
        }
        if (trueCount == length) {
            return new AllTrue(length);
        }
        return new Values(words, length, trueCount);
    }

    /// This mask keeping only its first `limit` kept rows, as Rust's `Mask::limit`.
    ///
    /// @param limit the most rows to keep
    /// @return this mask when it keeps no more than `limit` rows, else the truncated mask
    default Mask limit(int limit) {
        if (trueCount() <= limit) {
            return this;
        }
        if (limit <= 0) {
            return new AllFalse(length());
        }
        // past the checks above, the mask keeps more than `limit >= 1` rows: AllTrue or Values
        long[] words = new long[(length() + 63) >>> 6];
        if (this instanceof Values v) {
            int remaining = limit;
            for (int w = 0; remaining > 0; w++) {
                long word = v.words[w];
                int count = Long.bitCount(word);
                if (count <= remaining) {
                    words[w] = word;
                    remaining -= count;
                } else {
                    for (; remaining > 0; remaining--) {
                        long lowest = word & -word;
                        words[w] |= lowest;
                        word ^= lowest;
                    }
                }
            }
        } else {
            Arrays.fill(words, 0, limit >>> 6, -1L);
            if ((limit & 63) != 0) {
                words[limit >>> 6] = (1L << limit) - 1;
            }
        }
        return new Values(words, length(), limit);
    }

    /// Every row kept.
    ///
    /// @param length the row count
    record AllTrue(int length) implements Mask {
        @Override
        public int trueCount() {
            return length;
        }
    }

    /// No row kept.
    ///
    /// @param length the row count
    record AllFalse(int length) implements Mask {
        @Override
        public int trueCount() {
            return 0;
        }
    }

    /// Some rows kept, at least one and not all, as Rust's `MaskValues`. The index and run forms
    /// are built from the bitmap on first use and published whole through a volatile field, so
    /// threads sharing a mask may build one twice but never see a partial one.
    final class Values implements Mask {

        private final long[] words;
        private final int length;
        private final int trueCount;
        private volatile int[] indices;
        private volatile int[] slices;

        private Values(long[] words, int length, int trueCount) {
            this.words = words;
            this.length = length;
            this.trueCount = trueCount;
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public int trueCount() {
            return trueCount;
        }

        /// The mask keeping the rows `indices`, as Rust's `Mask::from_indices`; the indices are
        /// kept as the mask's index form. Package-private: the indices are trusted, not checked.
        ///
        /// @param length  the row count
        /// @param indices the kept rows, strictly ascending, each in `[0, length)`; taken over
        /// @return the mask
        static Mask fromIndices(int length, int[] indices) {
            if (indices.length == 0) {
                return new AllFalse(length);
            }
            if (indices.length == length) {
                return new AllTrue(length);
            }
            long[] words = new long[(length + 63) >>> 6];
            for (int i : indices) {
                words[i >>> 6] |= 1L << i;
            }
            Values values = new Values(words, length, indices.length);
            values.indices = indices;
            return values;
        }

        /// The fraction of rows kept, in `(0, 1)`.
        double density() {
            return (double) trueCount / length;
        }

        /// The kept rows, ascending, built from the bitmap a word at a time on first use. The array
        /// is shared by every caller and must not be modified.
        int[] indices() {
            int[] result = indices;
            if (result == null) {
                result = new int[trueCount];
                int k = 0;
                for (int w = 0; w < words.length; w++) {
                    long word = words[w];
                    while (word != 0) {
                        result[k++] = (w << 6) + Long.numberOfTrailingZeros(word);
                        word &= word - 1;
                    }
                }
                indices = result;
            }
            return result;
        }

        /// The runs of consecutive kept rows as `start, end` pairs, `end` exclusive, ascending and
        /// not touching, built from the bitmap on first use. The array is shared by every caller and
        /// must not be modified.
        int[] slices() {
            int[] result = slices;
            if (result == null) {
                int[] runs = new int[16];
                int count = 0;
                int row = nextSet(0);
                while (row >= 0) {
                    int end = nextClear(row);
                    if (count + 2 > runs.length) {
                        runs = Arrays.copyOf(runs, runs.length * 2);
                    }
                    runs[count++] = row;
                    runs[count++] = end;
                    row = end < length ? nextSet(end) : -1;
                }
                result = Arrays.copyOf(runs, count);
                slices = result;
            }
            return result;
        }

        private int nextSet(int from) {
            int w = from >>> 6;
            long word = words[w] & -1L << from;
            while (word == 0) {
                if (++w == words.length) {
                    return -1;
                }
                word = words[w];
            }
            return (w << 6) + Long.numberOfTrailingZeros(word);
        }

        private int nextClear(int from) {
            int w = from >>> 6;
            long word = ~words[w] & -1L << from;
            while (word == 0) {
                if (++w == words.length) {
                    return length;
                }
                word = ~words[w];
            }
            return Math.min(length, (w << 6) + Long.numberOfTrailingZeros(word));
        }
    }
}
