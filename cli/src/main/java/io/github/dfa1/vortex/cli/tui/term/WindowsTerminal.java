package io.github.dfa1.vortex.cli.tui.term;

import java.io.IOException;
import java.io.InputStream;
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

/// Windows console raw-mode implementation via kernel32.dll.
///
/// Toggles `ENABLE_VIRTUAL_TERMINAL_PROCESSING` on stdout (Win10 1809+
/// required) so ANSI sequences in [Ansi] render natively. Stdin runs with
/// line-input + echo + processed-input disabled and VT input enabled so xterm
/// arrow sequences arrive intact.
///
/// `GetConsoleScreenBufferInfo` drives [#size()]; we report the visible
/// window rect, not the scrollback buffer.
@SuppressWarnings("java:S106") // terminal driver: writes control sequences straight to stdout
public final class WindowsTerminal implements Terminal {

    private static final long STD_INPUT_HANDLE = -10L;
    private static final long STD_OUTPUT_HANDLE = -11L;

    private static final int ENABLE_PROCESSED_INPUT = 0x0001;
    private static final int ENABLE_LINE_INPUT = 0x0002;
    private static final int ENABLE_ECHO_INPUT = 0x0004;
    private static final int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x0200;

    private static final int ENABLE_PROCESSED_OUTPUT = 0x0001;
    private static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;

    private static final int FILE_TYPE_PIPE = 0x0003;

    private static final Linker LINKER = Linker.nativeLinker();
    @SuppressWarnings("restricted") // FFM native binding: kernel32 lookup is this class's purpose
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
    private static final MethodHandle GET_FILE_TYPE = downcall("GetFileType",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle READ_FILE = downcall("ReadFile",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,   // hFile
                    ValueLayout.ADDRESS,   // lpBuffer
                    ValueLayout.JAVA_INT,  // nNumberOfBytesToRead
                    ValueLayout.ADDRESS,   // lpNumberOfBytesRead
                    ValueLayout.ADDRESS)); // lpOverlapped (NULL for sync)
    private static final MethodHandle GET_NUMBER_OF_CONSOLE_INPUT_EVENTS = downcall(
            "GetNumberOfConsoleInputEvents",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,   // hConsoleInput
                    ValueLayout.ADDRESS)); // lpcNumberOfEvents

    private final Arena arena;
    private final MemorySegment stdinHandle;
    private final MemorySegment stdoutHandle;
    private final int savedInMode;
    private final int savedOutMode;
    private final PrintStream out;
    private final InputStream stdinStream;
    private final Thread shutdownHook;
    private boolean closed;

    private WindowsTerminal(Arena arena, MemorySegment stdinHandle, MemorySegment stdoutHandle,
            int savedInMode, int savedOutMode) {
        this.arena = arena;
        this.stdinHandle = stdinHandle;
        this.stdoutHandle = stdoutHandle;
        this.savedInMode = savedInMode;
        this.savedOutMode = savedOutMode;
        this.out = System.out;
        this.stdinStream = new ConsoleInputStream();
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

            WindowsTerminal term = new WindowsTerminal(arena, stdin, stdout, inMode, outMode);
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
        } catch (Throwable _) {
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
        return KeyDecoder.next(stdinStream);
    }

    @Override
    public Optional<Key> readKey(Duration timeout) throws IOException {
        return KeyDecoder.nextWithTimeout(stdinStream, timeout);
    }

    @Override
    public void close() {
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

    /// Reads bytes directly from the Windows console handle via `ReadFile`,
    /// bypassing `System.in` which goes through JVM-internal CRT wrappers
    /// that ignore `SetConsoleMode` changes.
    @SuppressWarnings("java:S4929") // single-byte read per key is correct for console input
    private final class ConsoleInputStream extends InputStream {

        private final MemorySegment readBuf = arena.allocate(ValueLayout.JAVA_BYTE);
        private final MemorySegment bytesRead = arena.allocate(ValueLayout.JAVA_INT);
        private final MemorySegment eventCount = arena.allocate(ValueLayout.JAVA_INT);

        @Override
        public int read() throws IOException {
            try {
                int rc = (int) READ_FILE.invokeExact(stdinHandle, readBuf, 1, bytesRead, MemorySegment.NULL);
                if (rc == 0 || bytesRead.get(ValueLayout.JAVA_INT, 0) == 0) {
                    return -1;
                }
                return Byte.toUnsignedInt(readBuf.get(ValueLayout.JAVA_BYTE, 0));
            } catch (Throwable t) {
                throw new IOException(t);
            }
        }

        @Override
        public int available() {
            try {
                int rc = (int) GET_NUMBER_OF_CONSOLE_INPUT_EVENTS.invokeExact(stdinHandle, eventCount);
                return rc != 0 ? eventCount.get(ValueLayout.JAVA_INT, 0) : 0;
            } catch (Throwable _) {
                return 0;
            }
        }
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
        } catch (Throwable _) {
            // Best-effort: JVM is exiting; nothing useful to do.
        }
    }

    private static int readMode(Arena arena, MemorySegment handle) throws Throwable {
        MemorySegment slot = arena.allocate(4);
        if ((int) GET_CONSOLE_MODE.invokeExact(handle, slot) == 0) {
            // Under Git Bash / MSYS / MinTTY stdio is piped from the terminal
            // emulator rather than attached to a real Windows console, so
            // GetConsoleMode fails with ERROR_INVALID_HANDLE. Distinguish
            // that case so the user gets a pointer to `winpty` instead of
            // a bare "GetConsoleMode failed".
            int fileType = (int) GET_FILE_TYPE.invokeExact(handle);
            if (fileType == FILE_TYPE_PIPE) {
                throw new IOException(
                        "TUI requires a real Windows console. Git Bash / MinTTY pipes stdio, so "
                        + "console APIs fail. Re-run via `winpty <command>` or switch to "
                        + "Windows Terminal, PowerShell, or cmd.exe.");
            }
            throw new IOException("GetConsoleMode failed");
        }
        return slot.get(ValueLayout.JAVA_INT, 0);
    }

    private static MemorySegment invokeHandle(MethodHandle mh, long stdHandle) throws Throwable {
        return (MemorySegment) mh.invokeExact(stdHandle);
    }

    @SuppressWarnings("restricted") // FFM native binding: kernel32 downcalls are this class's purpose
    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                KERNEL32.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)),
                desc);
    }
}
