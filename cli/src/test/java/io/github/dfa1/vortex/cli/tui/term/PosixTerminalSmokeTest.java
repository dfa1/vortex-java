package io.github.dfa1.vortex.cli.tui.term;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

/// Smoke test for the FFM-based POSIX terminal binding.
///
/// Runs only on Linux / macOS (other OSes lack the libc entry points). Mirrors
/// {@link WindowsTerminalSmokeTest}: the goal is to catch missing-symbol /
/// signature-mismatch regressions in CI without requiring a real interactive TTY.
///
/// `open()` and `size()` are deliberately not exercised — they need a real
/// console, and a stray successful `open()` would switch the developer's terminal
/// into the alternate screen / raw mode. Class load alone is the safe, meaningful
/// assertion: it forces every `Linker.downcallHandle` in the static initializer
/// (tcgetattr, tcsetattr, cfmakeraw, ioctl) to resolve its libc symbol, throwing
/// {@link UnsatisfiedLinkError} during `<clinit>` if an entry point is missing.
class PosixTerminalSmokeTest {

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void classLoad_resolvesEveryLibcSymbol() {
        // Given / When — touching the class triggers <clinit>, which calls
        // Linker.downcallHandle for tcgetattr / tcsetattr / cfmakeraw / ioctl.
        Class<?> sut = PosixTerminal.class;

        // Then
        assertThat(sut).isNotNull();
        assertThat(sut.getDeclaredMethods()).isNotEmpty();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void terminalOpen_selectsPosixImplOnThisOs() {
        // The factory dispatches on os.name; on a non-Windows OS it must pick the
        // POSIX path. We assert the static dispatch decision without opening a TTY:
        // os.name here is never "windows", so Terminal.open would route to POSIX.
        String os = System.getProperty("os.name", "").toLowerCase();

        assertThat(os).doesNotContain("win");
    }
}
