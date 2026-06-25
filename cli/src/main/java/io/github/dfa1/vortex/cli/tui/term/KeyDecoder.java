package io.github.dfa1.vortex.cli.tui.term;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/// Translates raw stdin bytes into [Key] events.
///
/// Recognises common CSI sequences emitted by xterm-compatible terminals:
/// `ESC [ A/B/C/D` for arrows, `ESC [ 5~ / 6~` for PgUp/PgDn,
/// `ESC [ H / F` and `ESC [ 1~ / 4~` for Home/End. Any unrecognised
/// escape sequence is dropped and decoding continues with the next byte.
///
/// Stateless across reads - call [#next(InputStream)] for each event.
public final class KeyDecoder {

    private KeyDecoder() {
    }

    /// Reads the next key, returning empty if the timeout elapses before any
    /// input is available. Polls [InputStream#available()] on a 20 ms cadence.
    ///
    /// @param in raw input stream
    /// @param timeout maximum time to wait
    /// @return the decoded key, or empty on timeout
    /// @throws IOException if reading fails
    public static Optional<Key> nextWithTimeout(InputStream in, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (in.available() == 0) {
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.of(next(in));
    }

    /// Reads the next key from `in`, blocking until at least one byte arrives.
    ///
    /// @param in raw input stream (typically `System.in` in cbreak mode)
    /// @return the decoded key, or [Key.Eof] if the stream is at EOF
    /// @throws IOException if the underlying read fails
    public static Key next(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            return Key.Eof.INSTANCE;
        }
        if (b == 0x1B) {
            return readAfterEsc(in);
        }
        if (b == '\r' || b == '\n') {
            return Key.Enter.INSTANCE;
        }
        // DEL (0x7F) is what most terminals send for the Backspace key; 0x08 (BS) is the
        // legacy code some emit. Treat both as one editing key so the line editor can erase.
        if (b == 0x7F || b == 0x08) {
            return Key.Backspace.INSTANCE;
        }
        return new Key.Char((char) b);
    }

    private static Key readAfterEsc(InputStream in) throws IOException {
        // Bare ESC: no follow-up byte available within a short window.
        // We approximate by peeking via available(); proper terminal IO would
        // use a select() / VTIME timer, but this is enough for q/Esc quit.
        if (in.available() == 0) {
            return Key.Escape.INSTANCE;
        }
        int b1 = in.read();
        if (b1 != '[' && b1 != 'O') {
            return Key.Escape.INSTANCE;
        }
        int b2 = in.read();
        return switch (b2) {
            case 'A' -> Key.ArrowUp.INSTANCE;
            case 'B' -> Key.ArrowDown.INSTANCE;
            case 'C' -> Key.ArrowRight.INSTANCE;
            case 'D' -> Key.ArrowLeft.INSTANCE;
            case 'H' -> Key.Home.INSTANCE;
            case 'F' -> Key.End.INSTANCE;
            default -> readTildeSequence(in, b2);
        };
    }

    private static Key readTildeSequence(InputStream in, int firstDigit) throws IOException {
        if (firstDigit < '0' || firstDigit > '9') {
            return Key.Escape.INSTANCE;
        }
        int digit = firstDigit - '0';
        int next = in.read();
        if (next == -1) {
            return Key.Eof.INSTANCE;
        }
        // Two-digit codes like ESC [ 15~; collapse to single digit by ignoring extras.
        while (next >= '0' && next <= '9') {
            digit = digit * 10 + (next - '0');
            next = in.read();
            if (next == -1) {
                return Key.Eof.INSTANCE;
            }
        }
        if (next != '~') {
            return Key.Escape.INSTANCE;
        }
        return switch (digit) {
            case 1, 7 -> Key.Home.INSTANCE;
            case 4, 8 -> Key.End.INSTANCE;
            case 5 -> Key.PageUp.INSTANCE;
            case 6 -> Key.PageDown.INSTANCE;
            default -> Key.Escape.INSTANCE;
        };
    }
}
