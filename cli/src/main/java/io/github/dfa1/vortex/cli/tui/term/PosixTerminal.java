package io.github.dfa1.vortex.cli.tui.term;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Optional;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/// POSIX (Linux + macOS) raw-mode terminal implementation.
///
/// Uses libc `tcgetattr` / `cfmakeraw` / `tcsetattr` via FFM
/// to put stdin in non-canonical, no-echo mode. `ioctl(TIOCGWINSZ)`
/// queries the terminal size on every call (no SIGWINCH plumbing).
///
/// On entry: saves the original `termios`, switches to alt screen, hides
/// the cursor. On [#close()]: restores everything, even on exceptions, via a
/// shutdown hook that fires if the caller skips try-with-resources.
public final class PosixTerminal implements Terminal {

    private static final int STDIN_FD = 0;
    private static final int STDOUT_FD = 1;
    private static final int TCSANOW = 0;

    private static final long TIOCGWINSZ = isMac() ? 0x40087468L : 0x5413L;

    /// `struct termios` is at most 72 bytes (macOS); Linux glibc is 60.
    /// 128 is a comfortable upper bound and lets the same code work on both.
    private static final long TERMIOS_SIZE = 128;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();

    private static final MethodHandle TCGETATTR = downcall("tcgetattr",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle TCSETATTR = downcall("tcsetattr",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle CFMAKERAW = downcall("cfmakeraw",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    private static final MethodHandle IOCTL = LINKER.downcallHandle(
            LIBC.find("ioctl").orElseThrow(() -> new UnsatisfiedLinkError("ioctl")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
            Linker.Option.firstVariadicArg(2));

    private final Arena arena;
    private final MemorySegment savedTermios;
    private final PrintStream out;
    private final Thread shutdownHook;
    private boolean closed;

    private PosixTerminal(Arena arena, MemorySegment savedTermios) {
        this.arena = arena;
        this.savedTermios = savedTermios;
        this.out = System.out;
        this.shutdownHook = new Thread(this::restore, "posix-term-restore");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /// Enters raw mode and switches to the alternate screen.
    ///
    /// @return open terminal
    /// @throws IOException if `tcgetattr` or `tcsetattr` fails
    public static PosixTerminal open() throws IOException {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment saved = arena.allocate(TERMIOS_SIZE);
            MemorySegment raw = arena.allocate(TERMIOS_SIZE);
            int rc = (int) TCGETATTR.invokeExact(STDIN_FD, saved);
            if (rc != 0) {
                throw new IOException("tcgetattr failed: rc=" + rc);
            }
            MemorySegment.copy(saved, 0, raw, 0, TERMIOS_SIZE);
            CFMAKERAW.invokeExact(raw);
            rc = (int) TCSETATTR.invokeExact(STDIN_FD, TCSANOW, raw);
            if (rc != 0) {
                throw new IOException("tcsetattr failed: rc=" + rc);
            }
            PosixTerminal term = new PosixTerminal(arena, saved);
            term.out.print(Ansi.ENTER_ALT_SCREEN);
            term.out.print(Ansi.HIDE_CURSOR);
            term.out.print(Ansi.CLEAR_SCREEN);
            term.out.flush();
            return term;
        } catch (Throwable t) {
            arena.close();
            if (t instanceof IOException io) {
                throw io;
            }
            throw new IOException(t);
        }
    }

    @Override
    public Size size() {
        MemorySegment ws = arena.allocate(8);
        try {
            int rc = (int) IOCTL.invokeExact(STDOUT_FD, TIOCGWINSZ, ws);
            if (rc != 0) {
                return new Size(24, 80);
            }
        } catch (Throwable t) {
            return new Size(24, 80);
        }
        int rows = Short.toUnsignedInt(ws.get(ValueLayout.JAVA_SHORT, 0));
        int cols = Short.toUnsignedInt(ws.get(ValueLayout.JAVA_SHORT, 2));
        if (rows == 0 || cols == 0) {
            return new Size(24, 80);
        }
        return new Size(rows, cols);
    }

    @Override
    public void write(String s) {
        out.print(s);
    }

    @Override
    public void flush() {
        out.flush();
    }

    @Override
    public Key readKey() throws IOException {
        return KeyDecoder.next(System.in);
    }

    @Override
    public Optional<Key> readKey(Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.in.available() == 0) {
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.of(KeyDecoder.next(System.in));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
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
            @SuppressWarnings("unused")
            int rc = (int) TCSETATTR.invokeExact(STDIN_FD, TCSANOW, savedTermios);
        } catch (Throwable ignored) {
            // Best-effort: JVM is exiting; nothing useful to do.
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LIBC.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)),
                desc);
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
