package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.EncodingId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Rust 0.95's bitmap compression picks by its own estimates, not by measured size: run-end at
/// `runs × 8.125` bytes, sparse at `exceptions × 4 + 2`, the plain bitmap when neither beats it.
class BitmapCompressorTest {

    @Test
    void aFewScatteredNullsAreSparseWithConstantPatchValues() {
        // Given — 3 nulls in 4000 rows: 7 runs would cost 57 bytes, 3 exceptions 14
        boolean[] validity = new boolean[4000];
        Arrays.fill(validity, true);
        validity[10] = false;
        validity[1999] = false;
        validity[3500] = false;

        // When
        EncodeResult result = BitmapCompressor.compress(validity, EncodeTestHelper.testCtx());

        // Then — the patch values are all the minority value, so one constant
        assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_SPARSE);
        assertThat(result.rootNode().children()[1].encodingId()).isEqualTo(EncodingId.VORTEX_CONSTANT);
    }

    @Test
    void longRunsAreRunEnd() {
        // Given — 4 runs of 1000: 33 bytes of runs against 2002 of exceptions and a 500-byte bitmap
        boolean[] validity = new boolean[4000];
        for (int i = 0; i < validity.length; i++) {
            validity[i] = i / 1000 % 2 == 0;
        }

        // When
        EncodeResult result = BitmapCompressor.compress(validity, EncodeTestHelper.testCtx());

        // Then — Rust keeps the run values a plain bitmap
        assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_RUNEND);
        assertThat(result.rootNode().children()[1].encodingId()).isEqualTo(EncodingId.VORTEX_BOOL);
    }

    @Test
    void randomBitsStayABitmap() {
        // Given — about half the rows null at random: every estimate is worse than the bitmap
        Random random = new Random(7);
        boolean[] validity = new boolean[4000];
        for (int i = 0; i < validity.length; i++) {
            validity[i] = random.nextBoolean();
        }

        // When
        EncodeResult result = BitmapCompressor.compress(validity, EncodeTestHelper.testCtx());

        // Then
        assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_BOOL);
    }
}
