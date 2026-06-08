package io.github.dfa1.vortex.inspect.term;

import java.io.IOException;
import java.util.Optional;

/// Direct, dependency-free terminal abstraction.
///
/// Implementations toggle the OS console into raw / non-canonical mode on
/// [#open()] and restore the prior state on [#close()]. Output is plain bytes
/// to {@code System.out}; input is buffered keystrokes from {@code System.in}.
///
/// Usage:
/// ```
/// try (Terminal term = Terminal.open()) {
///     term.write(Ansi.CLEAR_SCREEN);
///     Key k = term.readKey();
///     ...
/// }
/// ```
public sealed interface Terminal extends AutoCloseable
        permits PosixTerminal, WindowsTerminal {

    /// Opens the platform-appropriate raw-mode terminal.
    ///
    /// Picks [PosixTerminal] on Linux / macOS and [WindowsTerminal] on Windows
    /// based on {@code os.name}.
    ///
    /// @return an open raw terminal handle
    /// @throws IOException if the OS-level setup fails
    static Terminal open() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return WindowsTerminal.open();
        }
        return PosixTerminal.open();
    }

    /// Current terminal size in cells.
    ///
    /// @return rows and columns at this moment (re-queried each call)
    Size size();

    /// Writes a string of bytes (ASCII / UTF-8) verbatim to the terminal.
    ///
    /// @param s text to send (may contain ANSI escapes)
    /// @throws IOException if the write fails
    void write(String s) throws IOException;

    /// Flushes any buffered output.
    ///
    /// @throws IOException if flush fails
    void flush() throws IOException;

    /// Blocks until a key is available, then returns the decoded event.
    ///
    /// @return next key
    /// @throws IOException if reading fails
    Key readKey() throws IOException;

    /// Reads a key with a wall-clock deadline. Returns {@link Optional#empty()}
    /// if the timeout elapses before any input is available.
    ///
    /// @param timeoutMs maximum time to wait, in milliseconds
    /// @return the decoded key, or empty on timeout
    /// @throws IOException if reading fails
    Optional<Key> readKey(long timeoutMs) throws IOException;

    /// Restores the original terminal mode and exits the alternate screen.
    ///
    /// Idempotent - safe to call multiple times.
    @Override
    void close();

    /// Terminal dimensions in character cells.
    ///
    /// @param rows number of rows
    /// @param cols number of columns
    record Size(int rows, int cols) {
    }
}
