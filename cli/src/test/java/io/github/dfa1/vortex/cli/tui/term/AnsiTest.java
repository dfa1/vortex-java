package io.github.dfa1.vortex.cli.tui.term;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnsiTest {

    private static final char ESC = (char) 0x1B;

    @Test
    void escConstant_isAsciiEscapeByte() {
        // Given / When / Then — every CSI sequence relies on this being 0x1B
        assertThat(String.valueOf(ESC)).isEqualTo(Ansi.ESC);
    }

    @Test
    void moveTo_formatsRowAndColumn() {
        // Given / When / Then
        assertThat(Ansi.moveTo(5, 12)).isEqualTo(ESC + "[5;12H");
    }

    @Test
    void fgAndBg_emitSgrCode() {
        // Given / When / Then
        assertThat(Ansi.fg(31)).isEqualTo(ESC + "[31m");
        assertThat(Ansi.bg(42)).isEqualTo(ESC + "[42m");
    }

    @Test
    void clearAndCursorConstants_startWithCsi() {
        // Given / When / Then — guard against accidental edits dropping the ESC prefix
        String csi = ESC + "[";
        assertThat(Ansi.CLEAR_SCREEN).startsWith(csi).endsWith("2J");
        assertThat(Ansi.CURSOR_HOME).startsWith(csi).endsWith("H");
        assertThat(csi + "?25l").isEqualTo(Ansi.HIDE_CURSOR);
        assertThat(csi + "?25h").isEqualTo(Ansi.SHOW_CURSOR);
        assertThat(csi + "?1049h").isEqualTo(Ansi.ENTER_ALT_SCREEN);
        assertThat(csi + "?1049l").isEqualTo(Ansi.EXIT_ALT_SCREEN);
        assertThat(csi + "0m").isEqualTo(Ansi.RESET);
    }
}
