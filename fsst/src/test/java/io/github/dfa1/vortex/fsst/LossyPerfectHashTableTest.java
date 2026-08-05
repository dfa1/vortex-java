package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LossyPerfectHashTableTest {

    @Test
    void lookup_realThreeByteSymbol_resolvesCode() {
        // Given — a single length-3 symbol "abc" at code 0.
        Symbol abc = Symbol.of("abc".getBytes(StandardCharsets.UTF_8), 0, 3);
        LossyPerfectHashTable sut = LossyPerfectHashTable.of(List.of(abc));

        // When — the input word starts with "abc" and carries an unrelated trailing byte that the
        // masked compare must ignore (the symbol is only 3 bytes).
        int result = sut.lookup(wordOf("abcZZZZZ"));

        // Then — packed as code << 8 | length; a hit is never 0.
        assertThat(result).isNotZero();
        assertThat(result >>> 8).isZero();
        assertThat(result & 0xFF).isEqualTo(3);
    }

    @Test
    void lookup_eightByteSymbol_resolvesFullWord() {
        // Given — a length-8 symbol fills the whole word, the upper length boundary.
        Symbol full = Symbol.of("ABCDEFGH".getBytes(StandardCharsets.UTF_8), 0, 8);
        LossyPerfectHashTable sut = LossyPerfectHashTable.of(List.of(full));

        // When
        int result = sut.lookup(wordOf("ABCDEFGH"));

        // Then
        assertThat(result).isNotZero();
        assertThat(result & 0xFF).isEqualTo(8);
    }

    @Test
    void lookup_lengthOneAndTwoSymbolsSkipped() {
        // Given — the hash table must ignore length 1-2 symbols (ShortCodeTable's job), so a table
        // built only from them holds nothing to match.
        Symbol a = Symbol.of("a".getBytes(StandardCharsets.UTF_8), 0, 1);
        Symbol ab = Symbol.of("ab".getBytes(StandardCharsets.UTF_8), 0, 2);
        LossyPerfectHashTable sut = LossyPerfectHashTable.of(List.of(a, ab));

        // When
        int result = sut.lookup(wordOf("ab"));

        // Then — 0 is the packed "no match".
        assertThat(result).isZero();
    }

    @Test
    void lookup_hashCollisionInGainOrder_higherGainWinsSlotLowerGainMisses() {
        // Given — two distinct length-3 symbols whose first 3 bytes hash to the SAME slot. Inserted
        // in descending-gain order (higher-gain first, per the load-bearing invariant), the first
        // one must keep the slot and the second must be rejected — never overwrite it.
        Symbol[] colliding = findCollidingPair();
        Symbol higherGain = colliding[0];
        Symbol lowerGain = colliding[1];
        LossyPerfectHashTable sut = LossyPerfectHashTable.of(List.of(higherGain, lowerGain));

        // When — look up each symbol's own bytes.
        int higher = sut.lookup(higherGain.packedBytes());
        int lower = sut.lookup(lowerGain.packedBytes());

        // Then — the higher-gain (first-inserted) symbol wins; the lower-gain one correctly misses
        // and does not corrupt or masquerade as the winner.
        assertThat(higher).isNotZero();
        assertThat(higher >>> 8).isZero();
        assertThat(lower).isZero();
    }

    @Test
    void lookup_hashCollisionButByteMismatch_reportsNoMatch() {
        // Given — one stored length-3 symbol, and an input word that hashes to the same slot but
        // does NOT byte-match it. Only the masked compare (not the hash alone) may gate a hit, so
        // this adversarial word must report no match — proving there are no false positives.
        Symbol stored = Symbol.of("abc".getBytes(StandardCharsets.UTF_8), 0, 3);
        LossyPerfectHashTable sut = LossyPerfectHashTable.of(List.of(stored));
        long adversarial = findWordCollidingWithButNotEqualTo(stored.packedBytes());

        // When
        int result = sut.lookup(adversarial);

        // Then
        assertThat(result).isZero();
    }

    /// Finds two distinct 3-byte symbols whose first three bytes hash to the same slot, so the test
    /// exercises a real collision against the actual (stable, package-visible) hash function rather
    /// than a hand-picked guess that could drift if the hash changes.
    private static Symbol[] findCollidingPair() {
        int base = 0x414141; // "AAA", three printable bytes; iterate the third byte upward.
        int firstSlot = LossyPerfectHashTable.slotFor(base);
        for (int candidate = base + 1; candidate <= 0xFFFFFF; candidate++) {
            if (LossyPerfectHashTable.slotFor(candidate) == firstSlot && candidate != base) {
                return new Symbol[]{new Symbol(base, 3), new Symbol(candidate, 3)};
            }
        }
        throw new AssertionError("no colliding 3-byte prefix found — hash function changed?");
    }

    /// Finds a 3-byte word that hashes to the same slot as `stored` but differs from it, so the
    /// masked compare (not the hash) is what must reject it.
    private static long findWordCollidingWithButNotEqualTo(long stored) {
        int storedPrefix = (int) (stored & 0x00FF_FFFFL);
        int storedSlot = LossyPerfectHashTable.slotFor(storedPrefix);
        for (int candidate = 0; candidate <= 0xFFFFFF; candidate++) {
            if (candidate != storedPrefix && LossyPerfectHashTable.slotFor(candidate) == storedSlot) {
                return candidate;
            }
        }
        throw new AssertionError("no colliding-but-different word found — hash function changed?");
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
