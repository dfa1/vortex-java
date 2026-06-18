package io.github.dfa1.vortex.cli.tui.term;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KeyDecoderTest {

    @Test
    void next_arrowUp_decodesCsiA() throws IOException {
        // Given
        ByteArrayInputStream in = bytes(0x1B, '[', 'A');

        // When
        Key result = KeyDecoder.next(in);

        // Then
        assertThat(result).isEqualTo(Key.ArrowUp.INSTANCE);
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
        Key result = KeyDecoder.next(in);

        // Then
        assertThat(result).isEqualTo(Key.Escape.INSTANCE);
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
        Key result = KeyDecoder.next(in);

        // Then
        assertThat(result).isInstanceOf(Key.Char.class);
        assertThat(((Key.Char) result).value()).isEqualTo('q');
    }

    @Test
    void next_eof_returnsEof() throws IOException {
        // Given — empty stream
        ByteArrayInputStream in = bytes();

        // When
        Key result = KeyDecoder.next(in);

        // Then
        assertThat(result).isEqualTo(Key.Eof.INSTANCE);
    }

    @Test
    void next_unknownCsiLetter_yieldsEscape() throws IOException {
        // Given — ESC [ Z is xterm reverse-tab; we don't recognise it
        ByteArrayInputStream in = bytes(0x1B, '[', 'Z');

        // When
        Key result = KeyDecoder.next(in);

        // Then — defensive: never emit garbage as Char on an unknown CSI
        assertThat(result).isEqualTo(Key.Escape.INSTANCE);
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

    @Test
    void next_ss3SequenceVariant_decodesArrows() throws IOException {
        // Given — DEC keypad / VT100 application-cursor mode uses {@code ESC O A}
        // instead of {@code ESC [ A}. KeyDecoder must accept both prefixes so the
        // TUI works under tmux / screen / putty configurations that ship SS3.
        assertThat(KeyDecoder.next(bytes(0x1B, 'O', 'A'))).isEqualTo(Key.ArrowUp.INSTANCE);
        assertThat(KeyDecoder.next(bytes(0x1B, 'O', 'D'))).isEqualTo(Key.ArrowLeft.INSTANCE);
        assertThat(KeyDecoder.next(bytes(0x1B, 'O', 'F'))).isEqualTo(Key.End.INSTANCE);
        assertThat(KeyDecoder.next(bytes(0x1B, 'O', 'H'))).isEqualTo(Key.Home.INSTANCE);
    }

    @Test
    void next_unknownEscapePrefix_yieldsEscape() throws IOException {
        // Given — {@code ESC X} (X is neither '[' nor 'O') is not a recognised
        // CSI or SS3 sequence. Must return Escape rather than try to decode further.
        ByteArrayInputStream in = bytes(0x1B, 'X', 'A');

        // When
        Key result = KeyDecoder.next(in);

        // Then
        assertThat(result).isEqualTo(Key.Escape.INSTANCE);
    }

    @Test
    void next_eofMidTildeSequence_returnsEof() throws IOException {
        // Given — partial {@code ESC [ 5} with no trailing tilde / digit
        ByteArrayInputStream in = bytes(0x1B, '[', '5');

        // When
        Key result = KeyDecoder.next(in);

        // Then — propagating Eof prevents the TUI from spinning on a truncated stream
        assertThat(result).isEqualTo(Key.Eof.INSTANCE);
    }

    @Test
    void next_tildeWithoutTrailingTildeChar_yieldsEscape() throws IOException {
        // Given — {@code ESC [ 5 x}: digit followed by a non-tilde non-digit.
        // The trailing character must be re-yielded by the next call, not lost.
        ByteArrayInputStream in = bytes(0x1B, '[', '5', 'x', 'y');

        // When
        Key first = KeyDecoder.next(in);
        Key second = KeyDecoder.next(in);

        // Then — 'x' is consumed as the terminator marker and dropped; 'y' is the next Char.
        assertThat(first).isEqualTo(Key.Escape.INSTANCE);
        assertThat(second).isInstanceOf(Key.Char.class);
        assertThat(((Key.Char) second).value()).isEqualTo('y');
    }

    @Test
    void next_controlByte_returnsCharWithRawValue() throws IOException {
        // Given — Ctrl-C is 0x03, no special handling — surfaced as Char so the
        // TUI's keymap can match it via {@code Key.Char(3)} if needed.
        ByteArrayInputStream in = bytes(0x03);

        // When
        Key result = KeyDecoder.next(in);

        // Then
        assertThat(result).isInstanceOf(Key.Char.class);
        assertThat(((Key.Char) result).value()).isEqualTo((char) 0x03);
    }

    @Nested
    class NextWithTimeout {

        @Test
        void inputAvailable_returnsKeyImmediately() throws IOException {
            // Given — 'q' already in the buffer; available() returns 1
            ByteArrayInputStream in = bytes('q');

            // When
            Optional<Key> result = KeyDecoder.nextWithTimeout(in, Duration.ofMillis(100));

            // Then
            assertThat(result).isPresent();
            assertThat(result.get()).isInstanceOf(Key.Char.class);
            assertThat(((Key.Char) result.get()).value()).isEqualTo('q');
        }

        @Test
        void noInputWithinTimeout_returnsEmpty() throws IOException {
            // Given — stream that always reports available() == 0 and blocks on read()
            InputStream empty = new InputStream() {
                @Override
                public int read() {
                    throw new AssertionError("read() must not be called on timeout path");
                }

                @Override
                public int available() {
                    return 0;
                }
            };

            // When
            Optional<Key> result = KeyDecoder.nextWithTimeout(empty, Duration.ofMillis(40));

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        void arrowKeySequenceAvailable_decodesFullSequence() throws IOException {
            // Given — ESC [ B (ArrowDown) ready in buffer
            ByteArrayInputStream in = bytes(0x1B, '[', 'B');

            // When
            Optional<Key> result = KeyDecoder.nextWithTimeout(in, Duration.ofMillis(100));

            // Then
            assertThat(result).contains(Key.ArrowDown.INSTANCE);
        }

        @Test
        void interruptedThread_returnsEmpty() throws IOException {
            // Given — thread pre-interrupted; available() returns 0 so loop is entered
            InputStream blocked = new InputStream() {
                @Override
                public int read() {
                    throw new AssertionError("read() must not be called");
                }

                @Override
                public int available() {
                    return 0;
                }
            };
            Thread.currentThread().interrupt();

            // When
            Optional<Key> result = KeyDecoder.nextWithTimeout(blocked, Duration.ofSeconds(10));

            // Then — returns empty rather than blocking; interrupt flag is restored
            assertThat(result).isEmpty();
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    private static ByteArrayInputStream bytes(int... bs) {
        byte[] out = new byte[bs.length];
        for (int i = 0; i < bs.length; i++) {
            out[i] = (byte) bs[i];
        }
        return new ByteArrayInputStream(out);
    }
}
