package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.EncodingId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
    void aLoneExceptionAtRowZeroKeepsAPrimitiveIndex() {
        // Given — one null, at row 0, as a dict values pool's null entry sits: the one index is 0,
        // which a 0-bit pack stores in no bytes, but Rust's compressor finds the one-value array
        // constant first, and a constant is not smaller, so it stays primitive
        boolean[] validity = new boolean[100];
        Arrays.fill(validity, true);
        validity[0] = false;

        // When
        EncodeResult result = BitmapCompressor.compress(validity, EncodeTestHelper.testCtx());

        // Then
        assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_SPARSE);
        assertThat(result.rootNode().children()[0].encodingId()).isEqualTo(EncodingId.VORTEX_PRIMITIVE);
    }

    /// Rust drops an all-true bitmap once `constant(true)` is smaller: 3 bitmap bytes, from 17
    /// rows. A nullable array the compressor saw with no null then has no validity child.
    @ParameterizedTest(name = "{0} rows, one null: {1}")
    @CsvSource({"16, false, false", "17, false, true", "4000, false, true", "4000, true, false"})
    void anAllValidBitmapIsDroppedOnceAConstantIsSmaller(int rows, boolean oneNull, boolean expected) {
        // Given
        boolean[] validity = new boolean[rows];
        Arrays.fill(validity, true);
        validity[rows - 1] = !oneNull;

        // When
        boolean result = BitmapCompressor.isAllValid(validity);

        // Then
        assertThat(result).isEqualTo(expected);
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

    /// Rust's FSST rebuilds its validity from the mask of the bitmap it compressed, so the padding
    /// bits past the last row are what decoding that compressed bitmap left: an all-set buffer for
    /// a sparse with a `true` fill or a run-end prefilled with `true`, zeroed bytes otherwise. Each
    /// shape's last row is valid, so bit 0 of the last byte is set and the rest is padding.
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // one null: sparse, fill true
            "'sparse, fill true', 4001, 1, 0xff",
            // a valid row in every 1000: sparse with a false fill decodes from zeroed bytes
            "'sparse, fill false', 4001, 0, 0x01",
            // runs of 1000, 5 of them: run-end under 32 runs appends to zeroed bytes
            "'run-end, few runs', 4001, 2, 0x01",
            // runs of 1000, 41 of them, 21 true: run-end over 32 runs prefills true
            "'run-end, many runs', 40001, 2, 0xff"})
    void aMaskBitmapPadsAsRustDecodesItsCompressedForm(String name, int rows, int pattern, int lastByte) {
        // Given
        boolean[] validity = new boolean[rows];
        for (int i = 0; i < rows; i++) {
            validity[i] = switch (pattern) {
                case 0 -> i % 1000 == 0;
                case 1 -> i != 10;
                default -> i / 1000 % 2 == 0;
            };
        }

        // When
        EncodeResult result = BitmapCompressor.maskBitmap(validity, EncodeTestHelper.testCtx());

        // Then — always a plain bitmap; only the padding of the last byte depends on the decode
        assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_BOOL);
        MemorySegment bits = result.buffers().getFirst();
        assertThat(Byte.toUnsignedInt(bits.get(ValueLayout.JAVA_BYTE, bits.byteSize() - 1))).isEqualTo(lastByte);
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
