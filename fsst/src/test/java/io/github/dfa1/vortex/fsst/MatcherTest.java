package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatcherTest {

    // A mixed-length table exercising every tier: code 0 = "ABCDEFGH" (8), code 1 = "abc" (3),
    // code 2 = "ab" (2), code 3 = "a" (1). Gain-descending order is assumed by construction; the
    // codes are the list indices.
    private static final List<Symbol> SYMBOLS = List.of(
            Symbol.of("ABCDEFGH".getBytes(StandardCharsets.UTF_8), 0, 8),
            Symbol.of("abc".getBytes(StandardCharsets.UTF_8), 0, 3),
            Symbol.of("ab".getBytes(StandardCharsets.UTF_8), 0, 2),
            Symbol.of("a".getBytes(StandardCharsets.UTF_8), 0, 1));

    @Test
    void longestMatch_eightByteSymbol_picksHashTableMatch() {
        // Given
        Matcher sut = Matcher.of(SYMBOLS);

        // When
        int result = sut.longestMatch(wordOf("ABCDEFGH"));

        // Then — the longest (8-byte) symbol wins.
        assertThat(Matcher.codeOf(result)).isZero();
        assertThat(Matcher.lengthOf(result)).isEqualTo(8);
    }

    @Test
    void longestMatch_threeByteSymbol_picksHashTableOverShorterShortCodes() {
        // Given — input "abc..." matches "abc" (3), "ab" (2), and "a" (1); the 3-byte hash-table
        // match must win over the 1/2-byte short-code candidates.
        Matcher sut = Matcher.of(SYMBOLS);

        // When
        int result = sut.longestMatch(wordOf("abcxxxxx"));

        // Then
        assertThat(Matcher.codeOf(result)).isEqualTo(1);
        assertThat(Matcher.lengthOf(result)).isEqualTo(3);
    }

    @Test
    void longestMatch_hashMissFallsBackToTwoByteShortCode() {
        // Given — input "abz..." misses the 3-byte "abc" but matches "ab" (2); the matcher must
        // fall back through the tiers to the short-code table's 2-byte answer.
        Matcher sut = Matcher.of(SYMBOLS);

        // When
        int result = sut.longestMatch(wordOf("abzxxxxx"));

        // Then
        assertThat(Matcher.codeOf(result)).isEqualTo(2);
        assertThat(Matcher.lengthOf(result)).isEqualTo(2);
    }

    @Test
    void longestMatch_hashMissAndTwoByteMissFallsBackToSingleByte() {
        // Given — input "axz..." misses "abc" and "ab" but matches the length-1 "a".
        Matcher sut = Matcher.of(SYMBOLS);

        // When
        int result = sut.longestMatch(wordOf("axzxxxxx"));

        // Then
        assertThat(Matcher.codeOf(result)).isEqualTo(3);
        assertThat(Matcher.lengthOf(result)).isEqualTo(1);
    }

    @Test
    void longestMatch_unmatchedByte_signalsNoMatch() {
        // Given — the byte 'z' matches nothing at any tier; the caller must escape it.
        Matcher sut = Matcher.of(SYMBOLS);

        // When
        int result = sut.longestMatch(wordOf("zzzzzzzz"));

        // Then
        assertThat(Matcher.lengthOf(result)).isZero();
        assertThat(Matcher.codeOf(result)).isEqualTo(ShortCodeTable.NO_CODE);
    }

    /// Packs the low bytes of `s` LSB-first into an 8-byte word, matching the reader's
    /// little-endian interpretation of the input at a match position.
    private static long wordOf(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        long word = 0;
        for (int k = 0; k < bytes.length && k < 8; k++) {
            word |= Byte.toUnsignedLong(bytes[k]) << (k * 8);
        }
        return word;
    }
}
