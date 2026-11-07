package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SampleTest {

    @Test
    void draw_largeInput_totalSampledBytesPassTheTargetByAtMostALine() {
        // Given a corpus far larger than the 16KB target, so it is sampled down
        byte[][] rows = repeatedRows("the quick brown fox jumps over the lazy dog ", 5_000);

        // When
        Sample result = Sample.draw(rows);

        // Then Rust draws lines until the target is reached, so the last one may pass it
        assertThat(result.sampled()).isTrue();
        assertThat(result.bytes().length).isGreaterThanOrEqualTo(Sample.TARGET_SAMPLE_BYTES);
        assertThat(totalBytes(result))
                .isGreaterThanOrEqualTo(Sample.TARGET_SAMPLE_BYTES)
                .isLessThan(Sample.TARGET_SAMPLE_BYTES + Sample.LINE_BYTES);
    }

    @Test
    void draw_smallInput_keepsEveryRowWholeAsALine() {
        // Given a corpus under the target, with an empty row among them
        byte[][] rows = {bytes("abc"), new byte[0], bytes("de"), bytes("fghij")};

        // When
        Sample result = Sample.draw(rows);

        // Then every row is a line, the empty one too: training skips lines by their index
        assertThat(result.sampled()).isFalse();
        assertThat(result.lineCount()).isEqualTo(4);
        assertThat(result.lineEnd(1) - result.lineStart(1)).isZero();
        assertThat(totalBytes(result)).isEqualTo(10);
        assertThat(new String(result.bytes(), StandardCharsets.UTF_8)).isEqualTo("abcdefghij");
    }

    @Test
    void draw_sampledCorpusOfMostlyEmptyRows_walksPastTheEmptyOnes() {
        // Given 10,000 rows of which every hundredth holds 400 bytes: 40KB, sampled
        byte[][] rows = new byte[10_000][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = i % 100 == 0 ? bytes("y".repeat(400)) : new byte[0];
        }

        // When
        Sample result = Sample.draw(rows);

        // Then every line is a slice of a non-empty row, and the target is reached
        assertThat(result.sampled()).isTrue();
        assertThat(totalBytes(result)).isGreaterThanOrEqualTo(Sample.TARGET_SAMPLE_BYTES);
        for (int line = 0; line < result.lineCount(); line++) {
            assertThat(result.lineEnd(line) - result.lineStart(line)).isPositive();
        }
    }

    @Test
    void draw_emptyRowsOnly_givesAnEmptyLineEach() {
        // Given every row empty
        byte[][] rows = {new byte[0], new byte[0], new byte[0]};

        // When
        Sample result = Sample.draw(rows);

        // Then three empty lines, no bytes
        assertThat(result.lineCount()).isEqualTo(3);
        assertThat(result.bytes()).isEmpty();
    }

    @Test
    void draw_longRows_takeLinesOfAtMostFiveHundredTwelveBytes() {
        // Given rows of 5000 bytes each, 20 of them: 100KB, sampled
        byte[][] rows = repeatedRows("x".repeat(5_000), 20);

        // When
        Sample result = Sample.draw(rows);

        // Then
        for (int line = 0; line < result.lineCount(); line++) {
            assertThat(result.lineEnd(line) - result.lineStart(line)).isBetween(1, Sample.LINE_BYTES);
        }
    }

    @Test
    void draw_sameRows_drawTheSameSampleAsTheContiguousOverload() {
        // Given
        byte[][] rows = repeatedRows("deterministic sampling must reproduce ", 2_000);

        // When
        Sample perRow = Sample.draw(rows);
        Sample contiguous = Sample.draw(flatten(rows), offsets(rows), rows.length);

        // Then a fixed hash sequence, no seed: the same lines either way
        assertThat(contiguous.bytes()).isEqualTo(perRow.bytes());
        assertThat(contiguous.lineCount()).isEqualTo(perRow.lineCount());
    }

    @Test
    void hash_isRustsFsstHash() {
        // Given Rust's `value.wrapping_mul(2971215073) ^ value.wrapping_shr(15)`

        // When
        long zero = Sample.hash(0);
        long one = Sample.hash(1);
        long minusOne = Sample.hash(-1L);

        // Then
        assertThat(zero).isZero();
        assertThat(one).isEqualTo(2971215073L);
        assertThat(minusOne).isEqualTo(-2971215073L ^ (-1L >>> 15));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[][] repeatedRows(String text, int count) {
        byte[] row = bytes(text);
        byte[][] rows = new byte[count][];
        for (int i = 0; i < count; i++) {
            rows[i] = row;
        }
        return rows;
    }

    private static byte[] flatten(byte[][] rows) {
        int total = 0;
        for (byte[] row : rows) {
            total += row.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] row : rows) {
            System.arraycopy(row, 0, out, pos, row.length);
            pos += row.length;
        }
        return out;
    }

    private static int[] offsets(byte[][] rows) {
        int[] out = new int[rows.length + 1];
        for (int i = 0; i < rows.length; i++) {
            out[i + 1] = out[i] + rows[i].length;
        }
        return out;
    }

    private static int totalBytes(Sample sample) {
        int total = 0;
        for (int line = 0; line < sample.lineCount(); line++) {
            total += sample.lineEnd(line) - sample.lineStart(line);
        }
        return total;
    }
}
