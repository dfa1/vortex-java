package io.github.dfa1.vortex.writer.encode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// The vectors of Rust's `histograms.rs` tests (u32 latents), so a divergence from Rust's binning
/// shows up here rather than as a size difference: notably a run of equal values straddling a bin
/// boundary goes whole to one bin, and a chunk smaller than the bin count yields no empty bins.
class PcoHistogramTest {

    private static final long U32_MAX_KEY = 0xFFFF_FFFFL ^ Long.MIN_VALUE;

    @Test
    void empty() {
        // Given
        long[] keys = keys(List.of());

        // When
        List<PcoHistBin> result = PcoHistogram.histogram(keys, 2, U32_MAX_KEY);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void single() {
        // Given
        long[] keys = keys(List.of(8L));

        // When
        List<PcoHistBin> result = PcoHistogram.histogram(keys, 0, U32_MAX_KEY);

        // Then
        assertThat(result).containsExactly(bin(1, 8, 8));
    }

    @Test
    void fewerValuesThanBinsMakesNoEmptyBins() {
        // Given — 9 values over 4 bins: the old Java histogram emitted zero-count bins here
        long[] keys = keys(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L));

        // When
        List<PcoHistBin> result = PcoHistogram.histogram(keys, 2, U32_MAX_KEY);

        // Then
        assertThat(result).containsExactly(bin(3, 1, 3), bin(2, 4, 5), bin(2, 6, 7), bin(2, 8, 9));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
    void distinctShuffled(long seed) {
        // Given
        List<Long> latents = new ArrayList<>();
        for (long i = 0; i < 100; i++) {
            latents.add(i);
        }
        Collections.shuffle(latents, new Random(seed));

        // When
        List<PcoHistBin> result = PcoHistogram.histogram(keys(latents), 2, U32_MAX_KEY);

        // Then
        assertThat(result).containsExactly(bin(25, 0, 24), bin(25, 25, 49), bin(25, 50, 74), bin(25, 75, 99));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
    void oneOutlierAboveConstant(long seed) {
        // Given
        List<Long> latents = filled(100, 0);
        latents.set(0, 1L);
        Collections.shuffle(latents, new Random(seed));

        // When
        List<PcoHistBin> result = PcoHistogram.histogram(keys(latents), 2, U32_MAX_KEY);

        // Then
        assertThat(result).containsExactly(bin(99, 0, 0), bin(1, 1, 1));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
    void oneOutlierBelowConstant(long seed) {
        // Given
        List<Long> latents = filled(100, 1);
        latents.set(0, 0L);
        Collections.shuffle(latents, new Random(seed));

        // When
        List<PcoHistBin> result = PcoHistogram.histogram(keys(latents), 2, U32_MAX_KEY);

        // Then
        assertThat(result).containsExactly(bin(1, 0, 0), bin(99, 1, 1));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
    void dominantRunTakesWholeBins(long seed) {
        // Given — 1×3, 97×5, 2×7
        List<Long> latents = filled(100, 5);
        latents.set(0, 3L);
        latents.set(1, 7L);
        latents.set(2, 7L);
        Collections.shuffle(latents, new Random(seed));

        // When
        List<PcoHistBin> fourBins = PcoHistogram.histogram(keys(latents), 2, U32_MAX_KEY);
        List<PcoHistBin> twoBins = PcoHistogram.histogram(keys(latents), 1, U32_MAX_KEY);

        // Then
        assertThat(fourBins).containsExactly(bin(1, 3, 3), bin(97, 5, 5), bin(2, 7, 7));
        assertThat(twoBins).containsExactly(bin(98, 3, 5), bin(2, 7, 7));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
    void runStraddlingBoundaryJoinsTheBinHoldingItsMiddle(long seed) {
        // Given — 2×3, 97×5, 1×7: the 5s span the boundary at 50 and join the upper bin
        List<Long> latents = filled(100, 5);
        latents.set(0, 3L);
        latents.set(1, 3L);
        latents.set(2, 7L);
        Collections.shuffle(latents, new Random(seed));

        // When
        List<PcoHistBin> result = PcoHistogram.histogram(keys(latents), 1, U32_MAX_KEY);

        // Then
        assertThat(result).containsExactly(bin(2, 3, 3), bin(98, 5, 7));
    }

    private static List<Long> filled(int n, long value) {
        return new ArrayList<>(Collections.nCopies(n, value));
    }

    private static long[] keys(List<Long> latents) {
        return latents.stream().mapToLong(l -> l ^ Long.MIN_VALUE).toArray();
    }

    private static PcoHistBin bin(long count, long lower, long upper) {
        return new PcoHistBin(lower ^ Long.MIN_VALUE, upper ^ Long.MIN_VALUE, count);
    }
}
