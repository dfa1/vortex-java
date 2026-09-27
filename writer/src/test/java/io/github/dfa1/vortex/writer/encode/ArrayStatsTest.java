package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: [ArrayStats#compute]'s distinct-count and most-frequent tracking (backed by an
/// open-addressing `long -> count` map, not a boxing `HashMap<Long, int[]>`) matches a brute-force
/// reference over both small hand-picked cases and large seeded-random inputs that force the map to
/// grow past its initial capacity.
class ArrayStatsTest {

    @Test
    void compute_emptyArray_returnsEmptySentinel() {
        // Given
        int[] data = {};

        // When
        ArrayStats result = ArrayStats.compute(PType.I32, data, StatsOptions.DISTINCT_AND_TOP);

        // Then
        assertThat(result).isEqualTo(ArrayStats.EMPTY);
    }

    @Test
    void compute_optionsNone_reportsValueCountOnly() {
        // Given
        int[] data = {1, 2, 3};

        // When
        ArrayStats result = ArrayStats.compute(PType.I32, data, StatsOptions.NONE);

        // Then
        assertThat(result.valueCount()).isEqualTo(3);
        assertThat(result.hasDistinctCount()).isFalse();
    }

    @Test
    void compute_withDuplicates_countsDistinctAndMostFrequent() {
        // Given
        int[] data = {5, 5, 5, 7, 7, 9};

        // When
        ArrayStats result = ArrayStats.compute(PType.I32, data, StatsOptions.DISTINCT_AND_TOP);

        // Then
        assertThat(result.distinctCount()).isEqualTo(3);
        assertThat(result.mostFrequentBits()).isEqualTo(5);
        assertThat(result.topFrequency()).isEqualTo(3);
    }

    @Test
    void compute_allDistinctSequence_stopsAtTheCapAndSaysSo() {
        // Given: mirrors FSST's codesOffsets child (a strictly-increasing prefix sum), the
        // real-world shape that first motivated de-boxing this counter — and the shape the
        // distinct-count cap exists for. Every value is distinct, so the scan passes n/2 + 1
        // distinct values halfway through and stops: continuing cannot change any encoder's
        // verdict, and on this shape that halves the hashed rows.
        int n = 5_000;
        long[] data = new long[n];
        for (int i = 0; i < n; i++) {
            data[i] = i * 37L;
        }

        // When
        ArrayStats result = ArrayStats.compute(PType.I64, data, StatsOptions.DISTINCT_AND_TOP);

        // Then — the count is a lower bound past the cap, never the exact n
        assertThat(result.distinctCapped()).isTrue();
        assertThat(result.distinctCount()).isGreaterThan(n / 2L).isLessThan(n);
        // Dict and Constant still decide correctly from the capped count
        assertThat(result.distinctCount() * 2).isGreaterThanOrEqualTo(n);
        assertThat(result.distinctCount()).isNotEqualTo(1L);
    }

    @Test
    void compute_lowCardinality_neverHitsTheCap() {
        // Given: 10 distinct values over 5000 rows — nowhere near n/2, so the scan must run to
        // completion and report exact stats. This is the case the cap must not disturb.
        int n = 5_000;
        long[] data = new long[n];
        for (int i = 0; i < n; i++) {
            data[i] = i % 10;
        }

        // When
        ArrayStats result = ArrayStats.compute(PType.I64, data, StatsOptions.DISTINCT_AND_TOP);

        // Then
        assertThat(result.distinctCapped()).isFalse();
        assertThat(result.distinctCount()).isEqualTo(10L);
        assertThat(result.topFrequency()).isEqualTo(500L);
    }

    @Test
    void compute_zeroValueIsNotMistakenForAnEmptySlot() {
        // Given: the internal map tracks occupancy via a zero count, not a zero key, so a
        // genuinely-zero data value repeated many times must still be counted correctly.
        long[] data = new long[1000];

        // When
        ArrayStats result = ArrayStats.compute(PType.I64, data, StatsOptions.DISTINCT_AND_TOP);

        // Then
        assertThat(result.distinctCount()).isEqualTo(1);
        assertThat(result.mostFrequentBits()).isZero();
        assertThat(result.topFrequency()).isEqualTo(1000);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100, 5_000, 200_000})
    void compute_seededRandomData_matchesBruteForceReference(int n) {
        // Given: a bounded value range forces both repeats (exercising most-frequent tracking)
        // and, at the larger sizes, enough distinct values to force the counter past its initial
        // capacity and through at least one grow().
        var rng = new Random(n);
        long[] data = new long[n];
        for (int i = 0; i < n; i++) {
            data[i] = rng.nextInt(n / 2 + 1);
        }
        Map<Long, Integer> reference = new HashMap<>();
        long expectedTopBits = 0;
        int expectedTopFreq = 0;
        for (long v : data) {
            int c = reference.merge(v, 1, Integer::sum);
            if (c > expectedTopFreq) {
                expectedTopFreq = c;
                expectedTopBits = v;
            }
        }

        // When
        ArrayStats result = ArrayStats.compute(PType.I64, data, StatsOptions.DISTINCT_AND_TOP);

        // Then
        assertThat(result.distinctCount()).isEqualTo(reference.size());
        assertThat(result.topFrequency()).isEqualTo(expectedTopFreq);
        // Several values can share the top frequency, and which one is named is not meaningful —
        // asserting one specific tied value pinned an implementation detail (it broke when the
        // counter's capacity changed). The invariant that matters is that the value named really
        // does occur that many times.
        assertThat(reference.get(result.mostFrequentBits()))
                .as("value %d reported as most frequent", result.mostFrequentBits())
                .isEqualTo(expectedTopFreq);
    }
}
