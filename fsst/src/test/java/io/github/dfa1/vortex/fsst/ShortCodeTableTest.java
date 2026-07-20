package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeTableTest {

    @Test
    void codeFor_singleByteSymbol_resolvesLengthOne() {
        // Given — one length-1 symbol 'a' at code 0.
        Symbol a = Symbol.of("a".getBytes(StandardCharsets.UTF_8), 0, 1);
        ShortCodeTable sut = ShortCodeTable.of(List.of(a));
        long word = wordOf("ax"); // second byte irrelevant: no length-2 symbol.

        // When
        int code = sut.codeFor(word);
        int length = sut.lengthFor(word);

        // Then
        assertThat(code).isEqualTo(0);
        assertThat(length).isEqualTo(1);
    }

    @Test
    void codeFor_twoByteSymbol_resolvesLengthTwoAndBeatsSingleByteFallback() {
        // Given — code 0 = 'a' (length 1), code 1 = "ab" (length 2). At input "ab" the length-2
        // symbol must win over the length-1 fallback for the low byte 'a'.
        Symbol a = Symbol.of("a".getBytes(StandardCharsets.UTF_8), 0, 1);
        Symbol ab = Symbol.of("ab".getBytes(StandardCharsets.UTF_8), 0, 2);
        ShortCodeTable sut = ShortCodeTable.of(List.of(a, ab));
        long word = wordOf("ab");

        // When
        int code = sut.codeFor(word);
        int length = sut.lengthFor(word);

        // Then
        assertThat(code).isEqualTo(1);
        assertThat(length).isEqualTo(2);
    }

    @Test
    void codeFor_singleByteFallbackWhenTwoByteDiffers() {
        // Given — "ab" is a length-2 symbol, but the input is "ax"; only the length-1 'a' fallback
        // applies, so the low byte must still resolve to the single-byte code.
        Symbol a = Symbol.of("a".getBytes(StandardCharsets.UTF_8), 0, 1);
        Symbol ab = Symbol.of("ab".getBytes(StandardCharsets.UTF_8), 0, 2);
        ShortCodeTable sut = ShortCodeTable.of(List.of(a, ab));
        long word = wordOf("ax");

        // When
        int code = sut.codeFor(word);
        int length = sut.lengthFor(word);

        // Then
        assertThat(code).isEqualTo(0);
        assertThat(length).isEqualTo(1);
    }

    @Test
    void codeFor_byteWithNoSymbol_resolvesNoMatch() {
        // Given — only 'a' is in the table; the byte 'z' has no length-1 or length-2 symbol.
        Symbol a = Symbol.of("a".getBytes(StandardCharsets.UTF_8), 0, 1);
        ShortCodeTable sut = ShortCodeTable.of(List.of(a));
        long word = wordOf("zz");

        // When
        int code = sut.codeFor(word);
        int length = sut.lengthFor(word);

        // Then — the caller must escape this byte.
        assertThat(code).isEqualTo(ShortCodeTable.NO_CODE);
        assertThat(length).isEqualTo(0);
    }

    @Test
    void codeFor_longSymbolsIgnored() {
        // Given — a length-3 symbol must not populate this table (it is the hash table's job).
        Symbol abc = Symbol.of("abc".getBytes(StandardCharsets.UTF_8), 0, 3);
        ShortCodeTable sut = ShortCodeTable.of(List.of(abc));
        long word = wordOf("abc");

        // When
        int length = sut.lengthFor(word);

        // Then
        assertThat(length).isEqualTo(0);
    }

    /// Packs the low bytes of `s` LSB-first into a word, matching the reader's little-endian
    /// interpretation of the input at a match position.
    private static long wordOf(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        long word = 0;
        for (int k = 0; k < bytes.length && k < 8; k++) {
            word |= Byte.toUnsignedLong(bytes[k]) << (k * 8);
        }
        return word;
    }
}
