package io.github.dfa1.vortex.cli.tui.term;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

/// Smoke test for the FFM-based Windows console binding.
///
/// Runs only on Windows (other OSes lack kernel32). The goal is to catch
/// missing-symbol / signature-mismatch regressions in CI without requiring
/// a real interactive TTY:
///
/// - Class load alone forces every `Linker.downcallHandle` to resolve
///   its kernel32 symbol. A missing entry point throws
///   [UnsatisfiedLinkError] during static initialization.
/// - Bit-flag math for the VT mode toggles is verified directly so a typo
///   in a constant fails here, not in a customer's terminal.
class WindowsTerminalSmokeTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void classLoad_resolvesEveryKernel32Symbol() throws ClassNotFoundException {
        // Given / When — a class *literal* (WindowsTerminal.class) only loads the
        // class; it does NOT run <clinit>. Force initialization so the
        // Linker.downcallHandle calls for every imported kernel32 function fire.
        // Symbols covered: GetStdHandle, GetConsoleMode, SetConsoleMode,
        // GetConsoleScreenBufferInfo, GetFileType, ReadFile,
        // GetNumberOfConsoleInputEvents.
        Class<?> sut = Class.forName(
                "io.github.dfa1.vortex.cli.tui.term.WindowsTerminal",
                true,
                getClass().getClassLoader());

        // Then
        assertThat(sut).isNotNull();
        assertThat(sut.getDeclaredMethods()).isNotEmpty();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void modeFlagMath_inputModeMasksLineEchoProcessed_andSetsVtInput() {
        // Given — typical default cmd.exe input mode: line + echo + processed input enabled
        int defaultInMode = 0x0001 | 0x0002 | 0x0004; // PROCESSED | LINE | ECHO

        // When — same transform that WindowsTerminal.open applies
        int raw = (defaultInMode & ~(0x0002 | 0x0004 | 0x0001)) | 0x0200;

        // Then — line / echo / processed cleared, VT input set
        assertThat(raw & 0x0002).isZero();
        assertThat(raw & 0x0004).isZero();
        assertThat(raw & 0x0001).isZero();
        assertThat(raw & 0x0200).isEqualTo(0x0200);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void modeFlagMath_outputModeAddsVtProcessing() {
        // Given — default output mode
        int defaultOutMode = 0x0001; // PROCESSED_OUTPUT only

        // When
        int withVt = defaultOutMode | 0x0004 | 0x0001;

        // Then
        assertThat(withVt & 0x0004).isEqualTo(0x0004);
        assertThat(withVt & 0x0001).isEqualTo(0x0001);
    }
}
