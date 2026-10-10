package io.github.dfa1.vortex.writer.encode;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class LongRadixSortTest {

    // Sizes straddle MIN_LENGTH so both the comparison and the radix path run; the bounds are the
    // value widths, where a constant byte is skipped (8 and 28 bits) and where none is (64).
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 511, 512, 513, 5_000, 70_000})
    void sort_matchesArraysSort_acrossValueWidths(int length) {
        // Given
        Random random = new Random(length);
        for (int bits : new int[]{1, 8, 28, 40, 64}) {
            long[] values = new long[length];
            for (int i = 0; i < length; i++) {
                values[i] = bits == 64 ? random.nextLong() : random.nextLong() >>> (64 - bits);
            }
            long[] expected = values.clone();
            Arrays.sort(expected);

            // When
            LongRadixSort.sort(values);

            // Then
            assertThat(values).as("%d values of %d bits", length, bits).containsExactly(expected);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {512, 4_096})
    void sort_ordersNegativesBeforePositives_andKeepsExtremes(int length) {
        // Given — the sign bit is the top bit of the top byte, flipped so signed order is byte order
        Random random = new Random(7);
        long[] values = new long[length];
        for (int i = 0; i < length; i++) {
            values[i] = random.nextInt(3) - 1;
        }
        values[0] = Long.MIN_VALUE;
        values[length - 1] = Long.MAX_VALUE;
        long[] expected = values.clone();
        Arrays.sort(expected);

        // When
        LongRadixSort.sort(values);

        // Then
        assertThat(values).containsExactly(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {600, 10_000})
    void sort_ofAConstantArray_isUnchanged(int length) {
        // Given — every byte position is constant, so no pass runs
        long[] values = new long[length];
        Arrays.fill(values, 42L);

        // When
        LongRadixSort.sort(values);

        // Then
        assertThat(values).containsOnly(42L);
    }
}
