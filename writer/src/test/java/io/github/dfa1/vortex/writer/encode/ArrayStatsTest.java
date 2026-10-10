package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

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
        ArrayStats result = ArrayStats.compute(PType.I32, data, StatsOptions.distinctAndTop());

        // Then
        assertThat(result).isEqualTo(ArrayStats.EMPTY);
    }

    @Test
    void compute_optionsNone_reportsValueCountOnly() {
        // Given
        int[] data = {1, 2, 3};

        // When
        ArrayStats result = ArrayStats.compute(PType.I32, data, StatsOptions.defaults());

        // Then
        assertThat(result.valueCount()).isEqualTo(3);
        assertThat(result.hasDistinctCount()).isFalse();
    }

    @Test
    void compute_withDuplicates_countsDistinctAndMostFrequent() {
        // Given
        int[] data = {5, 5, 5, 7, 7, 9};

        // When
        ArrayStats result = ArrayStats.compute(PType.I32, data, StatsOptions.distinctAndTop());

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
        ArrayStats result = ArrayStats.compute(PType.I64, data, StatsOptions.distinctAndTop());

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
        ArrayStats result = ArrayStats.compute(PType.I64, data, StatsOptions.distinctAndTop());

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
        ArrayStats result = ArrayStats.compute(PType.I64, data, StatsOptions.distinctAndTop());

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
        ArrayStats result = ArrayStats.compute(PType.I64, data, StatsOptions.distinctAndTop());

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

    /// One case per counting path `compute` can take for integers. They must all agree with a
    /// per-row hash: the dense counter and the run-aware hashing are shortcuts that change the
    /// cost of the scan, never its answer, or the cascade would pick different encodings.
    static Stream<Arguments> integerCountingPaths() {
        var rng = new Random(42L);
        // Dense, always: an 8-bit range regardless of length.
        byte[] narrow = new byte[3_000];
        for (int i = 0; i < narrow.length; i++) {
            narrow[i] = (byte) rng.nextInt(256);
        }
        // Dense because the range (10k, like ALP-encoded two-decimal prices) is under the length.
        int[] priceCodes = new int[50_000];
        for (int i = 0; i < priceCodes.length; i++) {
            priceCodes[i] = 5_000 + rng.nextInt(10_001);
        }
        // Hashed with long runs: wide values, each repeated 30 times (a date column).
        long[] runs = new long[30_000];
        for (int i = 0; i < runs.length; i++) {
            runs[i] = (i / 30) * 1_000_003L;
        }
        // Hashed, no runs, range wider than the array.
        long[] wide = new long[20_000];
        for (int i = 0; i < wide.length; i++) {
            wide[i] = rng.nextInt(4_000) * 1_000_000_007L;
        }
        // U64 straddling 2^63: signed min/max still bound the values, so dense indexing holds.
        long[] straddle = new long[5_000];
        for (int i = 0; i < straddle.length; i++) {
            straddle[i] = Long.MAX_VALUE - 50 + rng.nextInt(100);
        }
        // Dense with long runs (a date column): the run-aware dense counter, whose 64-value
        // chunks skip runs in one step. 3_001 rows leaves a tail shorter than a chunk.
        int[] dates = new int[3_001];
        for (int i = 0; i < dates.length; i++) {
            dates[i] = 18_000 + i / 50;
        }
        // Unsigned 16/32-bit above the signed range: the chunked widening must zero-extend.
        short[] u16 = new short[1_500];
        int[] u32 = new int[2_500];
        for (int i = 0; i < u32.length; i++) {
            u32[i] = 0x8000_0000 + (i / 7) * 65_537;
        }
        // Signed 16-bit straddling zero: sign extension, dense.
        short[] i16 = new short[1_500];
        for (int i = 0; i < u16.length; i++) {
            u16[i] = (short) (0xFF00 + rng.nextInt(200));
            i16[i] = (short) (rng.nextInt(400) - 200);
        }
        return Stream.of(
                Arguments.of(PType.I32, dates, Arrays.stream(dates).asLongStream().toArray()),
                Arguments.of(PType.U16, u16, widen(u16, true)),
                Arguments.of(PType.I16, i16, widen(i16, false)),
                Arguments.of(PType.U32, u32, Arrays.stream(u32).mapToLong(Integer::toUnsignedLong).toArray()),
                Arguments.of(PType.U8, narrow, widen(narrow, true)),
                Arguments.of(PType.I8, narrow, widen(narrow, false)),
                Arguments.of(PType.I32, priceCodes, Arrays.stream(priceCodes).asLongStream().toArray()),
                Arguments.of(PType.I64, runs, runs),
                Arguments.of(PType.I64, wide, wide),
                Arguments.of(PType.U64, straddle, straddle));
    }

    @ParameterizedTest
    @MethodSource("integerCountingPaths")
    void compute_everyIntegerCountingPath_matchesPerRowReference(PType ptype, Object data, long[] widened) {
        // Given: reference counts by brute force; ties name the smaller value, as both counters do
        Map<Long, Integer> reference = new HashMap<>();
        for (long v : widened) {
            reference.merge(v, 1, Integer::sum);
        }
        int expectedTopFreq = reference.values().stream().max(Integer::compare).orElseThrow();
        long expectedTopBits = reference.entrySet().stream()
                .filter(e -> e.getValue() == expectedTopFreq)
                .mapToLong(Map.Entry::getKey).min().orElseThrow();

        // When
        ArrayStats result = ArrayStats.compute(ptype, data, StatsOptions.distinctAndTop());

        // Then
        assertThat(result.distinctCapped()).isFalse();
        assertThat(result.distinctCount()).isEqualTo(reference.size());
        assertThat(result.topFrequency()).isEqualTo(expectedTopFreq);
        assertThat(result.mostFrequentBits()).isEqualTo(expectedTopBits);
    }

    @Test
    void compute_averageRunLength_isValueCountOverRuns() {
        // Given: a date-like column, each value repeated 30 times, 100 runs
        int[] data = new int[3_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = i / 30;
        }

        // When: run length is reported even when no distinct stats are requested, as in Rust
        ArrayStats result = ArrayStats.compute(PType.I32, data, StatsOptions.defaults());

        // Then
        assertThat(result.averageRunLength()).isEqualTo(30);
    }

    @Test
    void compute_averageRunLength_comparesFloatsAsValues() {
        // Given: Rust's float stats compare with `!=`, so -0.0 continues a run of 0.0 and every
        // NaN starts a new one: runs are [0.0, -0.0, 0.0] [NaN] [NaN] [1.0] -> 4 runs over 6 values
        double[] data = {0.0, -0.0, 0.0, Double.NaN, Double.NaN, 1.0};

        // When
        ArrayStats result = ArrayStats.compute(PType.F64, data, StatsOptions.defaults());

        // Then: integer division, as Rust's value_count / runs
        assertThat(result.averageRunLength()).isEqualTo(1);
    }

    private static long[] widen(short[] a, boolean unsigned) {
        long[] out = new long[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = unsigned ? Short.toUnsignedLong(a[i]) : a[i];
        }
        return out;
    }

    private static long[] widen(byte[] a, boolean unsigned) {
        long[] out = new long[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = unsigned ? Byte.toUnsignedLong(a[i]) : a[i];
        }
        return out;
    }
}
