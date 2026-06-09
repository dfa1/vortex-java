package io.github.dfa1.vortex.cli.tui.term;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/// Windows console raw-mode implementation via kernel32.dll.
///
/// Toggles {@code ENABLE_VIRTUAL_TERMINAL_PROCESSING} on stdout (Win10 1809+
/// required) so ANSI sequences in [Ansi] render natively. Stdin runs with
/// line-input + echo + processed-input disabled and VT input enabled so xterm
/// arrow sequences arrive intact.
///
/// {@code GetConsoleScreenBufferInfo} drives [#size()]; we report the visible
/// window rect, not the scrollback buffer.
public final class WindowsTerminal implements Terminal {

    private static final long STD_INPUT_HANDLE = -10L;
    private static final long STD_OUTPUT_HANDLE = -11L;

    private static final int ENABLE_PROCESSED_INPUT = 0x0001;
    private static final int ENABLE_LINE_INPUT = 0x0002;
    private static final int ENABLE_ECHO_INPUT = 0x0004;
    private static final int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x0200;

    private static final int ENABLE_PROCESSED_OUTPUT = 0x0001;
    private static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup(
            "kernel32", Arena.global());

    private static final MethodHandle GET_STD_HANDLE = downcall("GetStdHandle",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle GET_CONSOLE_MODE = downcall("GetConsoleMode",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SET_CONSOLE_MODE = downcall("SetConsoleMode",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle GET_CONSOLE_SCREEN_BUFFER_INFO = downcall(
            "GetConsoleScreenBufferInfo",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private final Arena arena;
    private final MemorySegment stdoutHandle;
    private final int savedInMode;
    private final int savedOutMode;
    private final PrintStream out;
    private final Thread shutdownHook;
    private boolean closed;

    private WindowsTerminal(Arena arena, MemorySegment stdoutHandle,
            int savedInMode, int savedOutMode) {
        this.arena = arena;
        this.stdoutHandle = stdoutHandle;
        this.savedInMode = savedInMode;
        this.savedOutMode = savedOutMode;
        this.out = System.out;
        this.shutdownHook = new Thread(this::restore, "windows-term-restore");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /// Enables VT processing on stdout and VT input on stdin.
    ///
    /// @return open terminal
    /// @throws IOException if console handles cannot be obtained or modes set
    public static WindowsTerminal open() throws IOException {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment stdin = invokeHandle(GET_STD_HANDLE, STD_INPUT_HANDLE);
            MemorySegment stdout = invokeHandle(GET_STD_HANDLE, STD_OUTPUT_HANDLE);
            int inMode = readMode(arena, stdin);
            int outMode = readMode(arena, stdout);

            int newIn = (inMode & ~(ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT
                    | ENABLE_PROCESSED_INPUT)) | ENABLE_VIRTUAL_TERMINAL_INPUT;
            int newOut = outMode | ENABLE_VIRTUAL_TERMINAL_PROCESSING | ENABLE_PROCESSED_OUTPUT;

            if ((int) SET_CONSOLE_MODE.invokeExact(stdin, newIn) == 0) {
                throw new IOException("SetConsoleMode(stdin) failed");
            }
            if ((int) SET_CONSOLE_MODE.invokeExact(stdout, newOut) == 0) {
                throw new IOException("SetConsoleMode(stdout) failed");
            }

            WindowsTerminal term = new WindowsTerminal(arena, stdout, inMode, outMode);
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
        // CONSOLE_SCREEN_BUFFER_INFO is 22 bytes: COORD(4) size, COORD(4) cursor,
        // WORD(2) attrs, SMALL_RECT(8) window, COORD(4) max. We only need window.
        MemorySegment info = arena.allocate(22);
        try {
            int rc = (int) GET_CONSOLE_SCREEN_BUFFER_INFO.invokeExact(stdoutHandle, info);
            if (rc == 0) {
                return new Size(24, 80);
            }
        } catch (Throwable t) {
            return new Size(24, 80);
        }
        int left = info.get(ValueLayout.JAVA_SHORT, 10);
        int top = info.get(ValueLayout.JAVA_SHORT, 12);
        int right = info.get(ValueLayout.JAVA_SHORT, 14);
        int bottom = info.get(ValueLayout.JAVA_SHORT, 16);
        int rows = bottom - top + 1;
        int cols = right - left + 1;
        if (rows <= 0 || cols <= 0) {
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
    public Optional<Key> readKey(long timeoutMs) throws IOException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
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
            MemorySegment stdin = invokeHandle(GET_STD_HANDLE, STD_INPUT_HANDLE);
            SET_CONSOLE_MODE.invokeExact(stdin, savedInMode);
            SET_CONSOLE_MODE.invokeExact(stdoutHandle, savedOutMode);
        } catch (Throwable ignored) {
            // Best-effort: JVM is exiting; nothing useful to do.
        }
    }

    private static int readMode(Arena arena, MemorySegment handle) throws Throwable {
        MemorySegment slot = arena.allocate(4);
        if ((int) GET_CONSOLE_MODE.invokeExact(handle, slot) == 0) {
            throw new IOException("GetConsoleMode failed");
        }
        return slot.get(ValueLayout.JAVA_INT, 0);
    }

    private static MemorySegment invokeHandle(MethodHandle mh, long stdHandle) throws Throwable {
        return (MemorySegment) mh.invokeExact(stdHandle);
    }

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                KERNEL32.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)),
                desc);
    }
}
