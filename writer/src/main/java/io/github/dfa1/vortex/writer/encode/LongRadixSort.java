package io.github.dfa1.vortex.writer.encode;

import java.util.Arrays;

/// Sorts a `long[]` in signed order, the order of [Arrays#sort(long[])], with an LSD radix sort
/// over digits of 8 to 13 bits, wider for a longer array so the counters stay small next to the data.
///
/// A digit that is the same in every value is skipped, so values that vary in `k` digits (a 28-bit
/// coordinate varies in 3, or 3 of 5) cost `k` scatter passes, not all of them. Pco sorts every chunk it
/// encodes (and its deltas), which made [Arrays#sort(long[])] most of its encode time.
final class LongRadixSort {

    private static final int MIN_DIGIT_BITS = 8;
    private static final int MAX_DIGIT_BITS = 13;

    /// Below this a comparison sort beats allocating the counters and the scratch array.
    private static final int MIN_LENGTH = 512;

    private LongRadixSort() {
    }

    /// Sorts `values` in ascending signed order.
    ///
    /// @param values the array to sort in place
    static void sort(long[] values) {
        int n = values.length;
        if (n < MIN_LENGTH) {
            Arrays.sort(values);
            return;
        }
        // About n / 8 buckets: a 64K chunk gets 13-bit digits (5 passes at most, 28-bit keys need 3).
        int bits = Math.max(MIN_DIGIT_BITS, Math.min(MAX_DIGIT_BITS, 31 - Integer.numberOfLeadingZeros(n) - 3));
        int buckets = 1 << bits;
        int passes = (64 + bits - 1) / bits;
        int[][] counts = new int[passes][buckets];
        for (long v : values) {
            for (int p = 0; p < passes; p++) {
                counts[p][digit(v, p, bits, passes)]++;
            }
        }
        long[] source = values;
        long[] target = new long[n];
        for (int p = 0; p < passes; p++) {
            int[] count = counts[p];
            if (count[digit(source[0], p, bits, passes)] == n) {
                continue;
            }
            int sum = 0;
            for (int d = 0; d < buckets; d++) {
                int c = count[d];
                count[d] = sum;
                sum += c;
            }
            for (long v : source) {
                target[count[digit(v, p, bits, passes)]++] = v;
            }
            long[] swap = source;
            source = target;
            target = swap;
        }
        if (source != values) {
            System.arraycopy(source, 0, values, 0, n);
        }
    }

    /// Digit `p` (0 = least significant) of `v`; the sign bit is flipped so signed order is unsigned order.
    private static int digit(long v, int p, int bits, int passes) {
        int shift = p * bits;
        int d = (int) (v >>> shift) & ((1 << bits) - 1);
        return p == passes - 1 ? d ^ (1 << (63 - shift)) : d;
    }
}
