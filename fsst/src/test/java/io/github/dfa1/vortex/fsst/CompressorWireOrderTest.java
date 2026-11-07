package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The order Rust's `fsst-rs` numbers a final table in (`finalize`): 2-byte symbols no longer symbol
/// starts with, then the 2-byte symbols one does (last taken first), then 3 to 8 bytes by length in the
/// order taken, then the single bytes.
class CompressorWireOrderTest {

    @Test
    void codesSortedByLength_followsRustsFinalize() {
        // Given symbols taken in this order: code 0 is "q" ... code 7 is "yz"
        Compressor sut = Compressor.of(List.of(
                symbol("q"),
                symbol("ab"),
                symbol("abc"),
                symbol("xy"),
                symbol("wxyz"),
                symbol("ab" + "d"),
                symbol("r"),
                symbol("yz")));

        // When
        int[] order = sut.codesSortedByLength();

        // Then 2-byte symbols nothing extends ("xy", "yz") first in the order taken; the extended
        // "ab" next; 3 bytes ("abc", "abd") then 4 ("wxyz"); single bytes ("q", "r") last
        assertThat(order).containsExactly(3, 7, 1, 2, 5, 4, 0, 6);
    }

    @Test
    void codesSortedByLength_twoByteSymbolsSharingAPrefixWithALongerOneComeLastTakenFirst() {
        // Given two 2-byte symbols each a prefix of a longer one, taken "ab" first
        Compressor sut = Compressor.of(List.of(symbol("ab"), symbol("cd"), symbol("abx"), symbol("cdx")));

        // When
        int[] order = sut.codesSortedByLength();

        // Then the later one ("cd") sits before the earlier ("ab"), then the 3-byte ones
        assertThat(order).containsExactly(1, 0, 2, 3);
    }

    @Test
    void codesSortedByLength_asksEverySlotOfRustsTable_theOnesAnEarlierGenerationLeftToo() {
        // Given a table of "ab", "cd" and "abx", and a later, smaller one of "ab" and "cd": Rust never
        // clears the slot past the new symbols, so "abx" is still there to start with "ab"
        Compressor earlier = Compressor.of(List.of(symbol("ab"), symbol("cd"), symbol("abx")));
        Compressor sut = Compressor.rebuild(List.of(symbol("ab"), symbol("cd")), earlier);

        // When
        int[] order = sut.codesSortedByLength();

        // Then "cd", which nothing starts with, before "ab"
        assertThat(order).containsExactly(1, 0);
    }

    @Test
    void codesSortedByLength_emptyTable_isEmpty() {
        // Given / When / Then
        assertThat(Compressor.of(List.of()).codesSortedByLength()).isEmpty();
    }

    private static Symbol symbol(String text) {
        return Symbol.of(text.getBytes(StandardCharsets.US_ASCII), 0, text.length());
    }
}
