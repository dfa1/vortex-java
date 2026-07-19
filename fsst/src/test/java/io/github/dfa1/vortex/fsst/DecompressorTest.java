package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class DecompressorTest {

    @Nested
    class SingleCode {

        @Test
        void decompress_singleByteSymbol_appendsOneByte() {
            // Given — code 0 maps to the single byte 'A'.
            Decompressor sut = Decompressor.of(new long[]{'A'}, new int[]{1});
            byte[] out = new byte[1];

            // When
            long result = sut.decompress(new byte[]{0x00}, 0, 1, out, 0);

            // Then
            assertThat(result).isEqualTo(1);
            assertThat(out).isEqualTo("A".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void decompress_multiByteSymbol_appendsAllBytesLsbFirst() {
            // Given — code 0 maps to the 4-byte symbol "abcd", packed LSB-first via Symbol.of.
            Symbol abcd = Symbol.of("abcd".getBytes(StandardCharsets.UTF_8), 0, 4);
            Decompressor sut = Decompressor.of(new long[]{abcd.packedBytes()}, new int[]{4});
            byte[] out = new byte[4];

            // When
            long result = sut.decompress(new byte[]{0x00}, 0, 1, out, 0);

            // Then
            assertThat(result).isEqualTo(4);
            assertThat(out).isEqualTo("abcd".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void decompress_escapeCode_copiesLiteralByte() {
            // Given — no symbols; the escape code copies the following literal byte verbatim.
            Decompressor sut = Decompressor.of(new long[0], new int[0]);
            byte[] out = new byte[1];

            // When
            long result = sut.decompress(new byte[]{(byte) Decompressor.ESCAPE, 'Z'}, 0, 2, out, 0);

            // Then
            assertThat(result).isEqualTo(1);
            assertThat(out).isEqualTo("Z".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nested
    class MultipleCodes {

        @Test
        void decompress_symbolsAndEscape_concatenateInOrder() {
            // Given — code 0 = "ab" (2 bytes), code 1 = "c" (1 byte), then an escaped 'd'.
            Symbol ab = Symbol.of("ab".getBytes(StandardCharsets.UTF_8), 0, 2);
            Decompressor sut = Decompressor.of(new long[]{ab.packedBytes(), 'c'}, new int[]{2, 1});
            byte[] compressed = {0x00, 0x01, (byte) Decompressor.ESCAPE, 'd'};
            byte[] out = new byte[4];

            // When
            long result = sut.decompress(compressed, 0, compressed.length, out, 0);

            // Then
            assertThat(result).isEqualTo(4);
            assertThat(out).isEqualTo("abcd".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void decompress_startEndSubrange_decodesOnlyTheGivenWindow() {
            // Given — a stream with a leading and trailing code that must be ignored when only the
            // middle window [1, 2) is requested.
            Decompressor sut = Decompressor.of(new long[]{'X', 'Y', 'Z'}, new int[]{1, 1, 1});
            byte[] compressed = {0x00, 0x01, 0x02};
            byte[] out = new byte[1];

            // When
            long result = sut.decompress(compressed, 1, 2, out, 0);

            // Then — only code 1 ('Y') is decoded.
            assertThat(result).isEqualTo(1);
            assertThat(out).isEqualTo("Y".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nested
    class MultipleRows {

        @Test
        void decompress_sequentialRows_doNotCorruptEachOther() {
            // Given — two independent rows written into the same output buffer at sequential
            // positions. Row 0 = "ab" (symbol), row 1 = escaped 'c'. A wrong outPos advance would
            // let row 1 overwrite row 0's tail, so decoding both and checking each byte catches it.
            Symbol ab = Symbol.of("ab".getBytes(StandardCharsets.UTF_8), 0, 2);
            Decompressor sut = Decompressor.of(new long[]{ab.packedBytes()}, new int[]{2});
            byte[] out = new byte[3];

            // When
            long afterRow0 = sut.decompress(new byte[]{0x00}, 0, 1, out, 0);
            long afterRow1 = sut.decompress(new byte[]{(byte) Decompressor.ESCAPE, 'c'}, 0, 2, out, afterRow0);

            // Then
            assertThat(afterRow0).isEqualTo(2);
            assertThat(afterRow1).isEqualTo(3);
            assertThat(Arrays.copyOfRange(out, 0, 2)).isEqualTo("ab".getBytes(StandardCharsets.UTF_8));
            assertThat(Arrays.copyOfRange(out, 2, 3)).isEqualTo("c".getBytes(StandardCharsets.UTF_8));
        }
    }
}
