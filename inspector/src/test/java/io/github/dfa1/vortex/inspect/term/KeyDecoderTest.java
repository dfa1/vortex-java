package io.github.dfa1.vortex.inspect.term;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class KeyDecoderTest {

    @Test
    void next_arrowUp_decodesCsiA() throws IOException {
        // Given
        ByteArrayInputStream in = bytes(0x1B, '[', 'A');

        // When
        Key sut = KeyDecoder.next(in);

        // Then
        assertThat(sut).isEqualTo(Key.ArrowUp.INSTANCE);
    }

    @Test
    void next_allArrows_decodeIndependently() throws IOException {
        // Given / When / Then
        assertThat(KeyDecoder.next(bytes(0x1B, '[', 'A'))).isEqualTo(Key.ArrowUp.INSTANCE);
        assertThat(KeyDecoder.next(bytes(0x1B, '[', 'B'))).isEqualTo(Key.ArrowDown.INSTANCE);
        assertThat(KeyDecoder.next(bytes(0x1B, '[', 'C'))).isEqualTo(Key.ArrowRight.INSTANCE);
        assertThat(KeyDecoder.next(bytes(0x1B, '[', 'D'))).isEqualTo(Key.ArrowLeft.INSTANCE);
    }

    @Test
    void next_homeAndEnd_decodeBothCsiAndTildeForms() throws IOException {
        // Given / When / Then — xterm sends ESC[H/F; rxvt and others send ESC[1~/4~
        assertThat(KeyDecoder.next(bytes(0x1B, '[', 'H'))).isEqualTo(Key.Home.INSTANCE);
        assertThat(KeyDecoder.next(bytes(0x1B, '[', 'F'))).isEqualTo(Key.End.INSTANCE);
        assertThat(KeyDecoder.next(bytes(0x1B, '[', '1', '~'))).isEqualTo(Key.Home.INSTANCE);
        assertThat(KeyDecoder.next(bytes(0x1B, '[', '4', '~'))).isEqualTo(Key.End.INSTANCE);
    }

    @Test
    void next_pageUpAndDown_decodeTildeSequences() throws IOException {
        // Given / When / Then
        assertThat(KeyDecoder.next(bytes(0x1B, '[', '5', '~'))).isEqualTo(Key.PageUp.INSTANCE);
        assertThat(KeyDecoder.next(bytes(0x1B, '[', '6', '~'))).isEqualTo(Key.PageDown.INSTANCE);
    }

    @Test
    void next_bareEscape_returnsEscapeWhenNoFollowupAvailable() throws IOException {
        // Given — single ESC byte with no further input
        ByteArrayInputStream in = bytes(0x1B);

        // When
        Key sut = KeyDecoder.next(in);

        // Then
        assertThat(sut).isEqualTo(Key.Escape.INSTANCE);
    }

    @Test
    void next_enterFromCrAndLf_bothDecodeToEnter() throws IOException {
        // Given / When / Then
        assertThat(KeyDecoder.next(bytes('\r'))).isEqualTo(Key.Enter.INSTANCE);
        assertThat(KeyDecoder.next(bytes('\n'))).isEqualTo(Key.Enter.INSTANCE);
    }

    @Test
    void next_printableChar_returnsChar() throws IOException {
        // Given
        ByteArrayInputStream in = bytes('q');

        // When
        Key sut = KeyDecoder.next(in);

        // Then
        assertThat(sut).isInstanceOf(Key.Char.class);
        assertThat(((Key.Char) sut).value()).isEqualTo('q');
    }

    @Test
    void next_eof_returnsEof() throws IOException {
        // Given — empty stream
        ByteArrayInputStream in = bytes();

        // When
        Key sut = KeyDecoder.next(in);

        // Then
        assertThat(sut).isEqualTo(Key.Eof.INSTANCE);
    }

    @Test
    void next_unknownCsiLetter_yieldsEscape() throws IOException {
        // Given — ESC [ Z is xterm reverse-tab; we don't recognise it
        ByteArrayInputStream in = bytes(0x1B, '[', 'Z');

        // When
        Key sut = KeyDecoder.next(in);

        // Then — defensive: never emit garbage as Char on an unknown CSI
        assertThat(sut).isEqualTo(Key.Escape.INSTANCE);
    }

    @Test
    void next_multiDigitTildeCode_handlesTwoDigits() throws IOException {
        // Given — ESC [ 15 ~ is xterm F5; we treat unknown numbers as Escape but
        // must still consume the trailing '~' rather than leak it as a character
        ByteArrayInputStream in = bytes(0x1B, '[', '1', '5', '~', 'x');

        // When
        Key first = KeyDecoder.next(in);
        Key second = KeyDecoder.next(in);

        // Then
        assertThat(first).isEqualTo(Key.Escape.INSTANCE);
        assertThat(second).isInstanceOf(Key.Char.class);
        assertThat(((Key.Char) second).value()).isEqualTo('x');
    }

    private static ByteArrayInputStream bytes(int... bs) {
        byte[] out = new byte[bs.length];
        for (int i = 0; i < bs.length; i++) {
            out[i] = (byte) bs[i];
        }
        return new ByteArrayInputStream(out);
    }
}
