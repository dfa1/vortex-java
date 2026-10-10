package io.github.dfa1.vortex.writer.encode;

import java.util.Arrays;

/// Sorts a `long[]` in signed order, the order of [Arrays#sort(long[])], with an LSD radix sort
/// over the 8 bytes.
///
/// A byte position that is the same in every value is skipped, so values that vary in `k` bytes
/// (a 28-bit coordinate varies in 4) cost `k` scatter passes, not 8.
/// Pco sorts every chunk it encodes (and its deltas), which made [Arrays#sort(long[])] most of its
/// encode time.
final class LongRadixSort {

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
        int[][] counts = new int[8][256];
        for (long v : values) {
            for (int b = 0; b < 8; b++) {
                counts[b][digit(v, b)]++;
            }
        }
        long[] source = values;
        long[] target = new long[n];
        for (int b = 0; b < 8; b++) {
            int[] count = counts[b];
            if (count[digit(source[0], b)] == n) {
                continue;
            }
            int sum = 0;
            for (int d = 0; d < 256; d++) {
                int c = count[d];
                count[d] = sum;
                sum += c;
            }
            for (long v : source) {
                target[count[digit(v, b)]++] = v;
            }
            long[] swap = source;
            source = target;
            target = swap;
        }
        if (source != values) {
            System.arraycopy(source, 0, values, 0, n);
        }
    }

    /// Byte `b` (0 = least significant) of `v`; the top byte is flipped so signed order is unsigned order.
    private static int digit(long v, int b) {
        int d = (int) (v >>> (b * 8)) & 0xFF;
        return b == 7 ? d ^ 0x80 : d;
    }
}
