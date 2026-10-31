package io.github.dfa1.vortex.cli.tui.term;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.time.Duration;
import java.util.Optional;

/// The platform-independent half of a raw-mode terminal: the alternate screen, output, key input,
/// and a close that restores the console, also from a shutdown hook when the caller never closes.
/// A platform supplies the console mode switch (restored by [#restoreMode()]), the key source and
/// the size.
@SuppressWarnings("java:S106") // terminal driver: writes control sequences straight to stdout
abstract class RawModeTerminal implements Terminal {

    /// The size reported when the platform cannot tell.
    static final Size DEFAULT_SIZE = new Size(24, 80);

    /// Native memory for the platform's calls, closed with the terminal.
    final Arena arena;
    private final PrintStream out = System.out;
    private final Thread shutdownHook;
    private boolean closed;

    RawModeTerminal(Arena arena, String hookName) {
        this.arena = arena;
        this.shutdownHook = new Thread(this::restore, hookName);
    }

    /// Arms the restore-on-exit hook, then switches to the alternate screen and hides the cursor.
    /// Called once the console is in raw mode.
    final void enter() {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        out.print(Ansi.ENTER_ALT_SCREEN);
        out.print(Ansi.HIDE_CURSOR);
        out.print(Ansi.CLEAR_SCREEN);
        out.flush();
    }

    /// The stream keys are read from.
    abstract InputStream input();

    /// Puts the console back in the mode it had before raw mode.
    ///
    /// @throws Throwable whatever the native call throws; the caller ignores it
    abstract void restoreMode() throws Throwable;

    @Override
    public final void write(String s) {
        out.print(s);
    }

    @Override
    public final void flush() {
        out.flush();
    }

    @Override
    public final Key readKey() throws IOException {
        return KeyDecoder.next(input());
    }

    @Override
    public final Optional<Key> readKey(Duration timeout) throws IOException {
        return KeyDecoder.nextWithTimeout(input(), timeout);
    }

    @Override
    public final void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException _) {
            // JVM already shutting down.
        }
        restore();
        arena.close();
    }

    private void restore() {
        try {
            out.print(Ansi.SHOW_CURSOR);
            out.print(Ansi.EXIT_ALT_SCREEN);
            out.print(Ansi.RESET);
            out.flush();
            restoreMode();
        } catch (Throwable _) {
            // Best-effort: JVM is exiting; nothing useful to do.
        }
    }
}
