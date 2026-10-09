package io.github.dfa1.vortex.writer.encode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Every expected value below was printed by a Rust program built on `rand` 0.10.2 (what vortex
/// 0.86.1 uses), running `StdRng::seed_from_u64(1234567890)` and a copy of `stratified_slices`.
class SampleRngTest {

    @Test
    void nextLong_matchesRustStdRng() {
        // Given
        SampleRng sut = new SampleRng(SampleRng.RUST_SAMPLE_SEED);

        // When
        long[] result = new long[6];
        for (int i = 0; i < result.length; i++) {
            result[i] = sut.nextLong();
        }

        // Then
        assertThat(result).containsExactly(3152425552648317937L, 3624611127238869736L,
                Long.parseUnsignedLong("11604213119256218922"), Long.parseUnsignedLong("18077168327633119347"),
                7062242673927548823L, Long.parseUnsignedLong("17378792140608686607"));
    }

    @Test
    void nextInt_matchesRustStdRng() {
        // Given
        SampleRng sut = new SampleRng(SampleRng.RUST_SAMPLE_SEED);

        // When
        int[] result = new int[6];
        for (int i = 0; i < result.length; i++) {
            result[i] = sut.nextInt();
        }

        // Then
        assertThat(Integer.toUnsignedString(result[0])).isEqualTo("2121772017");
        assertThat(Integer.toUnsignedString(result[2])).isEqualTo("3221242600");
        assertThat(Integer.toUnsignedString(result[5])).isEqualTo("2701816409");
    }

    @Test
    void nextIntInclusive_matchesRustRandomRange() {
        // Given: one draw per range, in order, including a one-value range that still consumes a word
        SampleRng sut = new SampleRng(SampleRng.RUST_SAMPLE_SEED);

        // When
        int[] result = {sut.nextIntInclusive(0, 4095), sut.nextIntInclusive(5, 5),
                sut.nextIntInclusive(100, 1_000_003), sut.nextIntInclusive(0, 3), sut.nextIntInclusive(7, 70_000)};

        // Then
        assertThat(result).containsExactly(2023, 5, 750031, 0, 45720);
    }

    @Test
    void stratifiedSample_drawsTheRunsRustDraws() {
        // Given: 131072 values (one write chunk), sampled at 1% as Rust does: 32 runs of 64
        int[] values = new int[131_072];
        for (int i = 0; i < values.length; i++) {
            values[i] = i;
        }

        // When
        int[] result = (int[]) CascadingCompressor.stratifiedSample(values, 2048, SampleRng.RUST_SAMPLE_SEED);

        // Then: the first value of each run is where Rust's slice starts
        assertThat(result).hasSize(2048);
        assertThat(new int[]{result[0], result[64], result[128], result[192], result[256], result[1984]})
                .containsExactly(1992, 4785, 11216, 13080, 19017, 128224);
        assertThat(result[63]).isEqualTo(2055);
    }

    @Test
    void stratifiedSample_unevenPartitionsMatchRust() {
        // Given: 100000 values do not divide into 16 partitions evenly
        int[] values = new int[100_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = i;
        }

        // When
        int[] result = (int[]) CascadingCompressor.stratifiedSample(values, 1024, SampleRng.RUST_SAMPLE_SEED);

        // Then
        assertThat(new int[]{result[0], result[64], result[128], result[960]})
                .containsExactly(3056, 7307, 17140, 95914);
    }
}
