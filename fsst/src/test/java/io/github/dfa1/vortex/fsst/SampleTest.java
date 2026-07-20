package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SampleTest {

    @Test
    void draw_largeInput_totalSampledBytesNearTarget() {
        // Given — a corpus far larger than the 16KB target, so sampling must cap the total.
        byte[][] rows = repeatedRows("the quick brown fox jumps over the lazy dog ", 5_000);

        // When
        Sample result = Sample.draw(rows, 1L);

        // Then — the total lands at the target exactly here, since every draw chunk is full-length;
        // in general it is bounded by TARGET_SAMPLE_BYTES and never overshoots (draws stop once the
        // target is reached). Allow a small tolerance for the last, possibly-truncated chunk.
        assertThat(totalBytes(result))
                .isLessThanOrEqualTo(Sample.TARGET_SAMPLE_BYTES)
                .isGreaterThan(Sample.TARGET_SAMPLE_BYTES - Sample.MAX_CHUNK_BYTES);
    }

    @Test
    void draw_smallInput_usesFullCorpus() {
        // Given — a corpus well under the target: no under-sampling should occur.
        byte[][] rows = repeatedRows("abc", 4); // 12 bytes total, << 16KB.

        // When
        Sample result = Sample.draw(rows, 1L);

        // Then — every input byte is represented.
        assertThat(totalBytes(result)).isEqualTo(12);
    }

    @Test
    void draw_allEmptyRows_producesEmptySample() {
        // Given — every row empty, so there is nothing to sample.
        byte[][] rows = {new byte[0], new byte[0], new byte[0]};

        // When
        Sample result = Sample.draw(rows, 1L);

        // Then
        assertThat(result.chunkCount()).isZero();
        assertThat(result.bytes()).isEmpty();
    }

    @Test
    void draw_sameSeed_producesByteIdenticalSample() {
        // Given
        byte[][] rows = repeatedRows("deterministic sampling must reproduce ", 2_000);

        // When
        Sample first = Sample.draw(rows, 99L);
        Sample second = Sample.draw(rows, 99L);

        // Then
        assertThat(second.bytes()).isEqualTo(first.bytes());
        assertThat(second.chunkCount()).isEqualTo(first.chunkCount());
    }

    @Test
    void chunkCountForGeneration_growsMonotonicallyToFullSample() {
        // Given
        byte[][] rows = repeatedRows("grow the fraction over generations ", 2_000);
        Sample sut = Sample.draw(rows, 7L);

        // When
        int gen0 = sut.chunkCountForGeneration(0);
        int gen4 = sut.chunkCountForGeneration(4);

        // Then — the first generation sees only a fraction; the last sees every chunk.
        assertThat(gen0).isGreaterThanOrEqualTo(1).isLessThan(sut.chunkCount());
        assertThat(gen4).isEqualTo(sut.chunkCount());
    }

    private static byte[][] repeatedRows(String text, int count) {
        byte[] row = text.getBytes(StandardCharsets.UTF_8);
        byte[][] rows = new byte[count][];
        for (int i = 0; i < count; i++) {
            rows[i] = row;
        }
        return rows;
    }

    private static int totalBytes(Sample sample) {
        int total = 0;
        for (int i = 0; i < sample.chunkCount(); i++) {
            total += sample.chunkEnd(i) - sample.chunkStart(i);
        }
        return total;
    }
}
