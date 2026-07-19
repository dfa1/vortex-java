package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SymbolTest {

    @Test
    void of_packsBytesLsbFirst() {
        // Given — "ab" should pack to 0x6261 (byte 'a'=0x61 in the low byte, 'b'=0x62 next).
        byte[] data = "ab".getBytes(StandardCharsets.UTF_8);

        // When
        Symbol result = Symbol.of(data, 0, 2);

        // Then
        assertThat(result.packedBytes()).isEqualTo(0x6261L);
        assertThat(result.length()).isEqualTo(2);
    }

    @Test
    void of_respectsOffset() {
        // Given — packing 2 bytes starting at offset 2 must read "cd", not "ab".
        byte[] data = "abcd".getBytes(StandardCharsets.UTF_8);

        // When
        Symbol result = Symbol.of(data, 2, 2);

        // Then
        assertThat(result.byteAt(0)).isEqualTo((byte) 'c');
        assertThat(result.byteAt(1)).isEqualTo((byte) 'd');
    }

    @Test
    void byteAt_returnsEachByteInOrder() {
        // Given
        Symbol sut = Symbol.of("WXYZ".getBytes(StandardCharsets.UTF_8), 0, 4);

        // When
        byte[] result = {sut.byteAt(0), sut.byteAt(1), sut.byteAt(2), sut.byteAt(3)};

        // Then
        assertThat(result).isEqualTo("WXYZ".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void of_eightByteSymbol_packsFullLong() {
        // Given — a full 8-byte symbol fills every byte of the long, the length boundary.
        byte[] data = "ABCDEFGH".getBytes(StandardCharsets.UTF_8);

        // When
        Symbol result = Symbol.of(data, 0, 8);

        // Then
        assertThat(result.length()).isEqualTo(8);
        assertThat(result.byteAt(7)).isEqualTo((byte) 'H');
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 9})
    void constructor_lengthOutOfRange_throws(int length) {
        // Given
        // When
        // Then
        assertThatThrownBy(() -> new Symbol(0L, length))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
