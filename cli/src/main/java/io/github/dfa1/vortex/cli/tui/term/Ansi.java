package io.github.dfa1.vortex.cli.tui.term;

/// ANSI / xterm CSI escape constants and small formatting helpers.
///
/// Sequences are plain ASCII once the leading `ESC` (0x1B) byte is included.
/// They're written verbatim to `System.out` once raw mode is enabled.
public final class Ansi {

    /// ESC (0x1B) - the byte every CSI sequence starts with.
    public static final String ESC = String.valueOf((char) 0x1B);

    /// Control Sequence Introducer: `ESC + '['`.
    public static final String CSI = ESC + "[";

    /// Clear entire screen.
    public static final String CLEAR_SCREEN = CSI + "2J";

    /// Erase from the cursor to the end of the current line.
    public static final String CLEAR_LINE_RIGHT = CSI + "K";

    /// Move cursor to top-left.
    public static final String CURSOR_HOME = CSI + "H";

    /// Reset all SGR attributes.
    public static final String RESET = CSI + "0m";

    /// Hide the cursor.
    public static final String HIDE_CURSOR = CSI + "?25l";

    /// Show the cursor.
    public static final String SHOW_CURSOR = CSI + "?25h";

    /// Switch to the alternate screen buffer.
    public static final String ENTER_ALT_SCREEN = CSI + "?1049h";

    /// Restore the primary screen buffer.
    public static final String EXIT_ALT_SCREEN = CSI + "?1049l";

    private Ansi() {
    }

    /// Move the cursor to (1-based) `row`, `col`.
    ///
    /// @param row 1-based row index
    /// @param col 1-based column index
    /// @return CSI sequence
    public static String moveTo(int row, int col) {
        return CSI + row + ";" + col + "H";
    }

    /// Standard SGR foreground colour (codes 30-37 normal, 90-97 bright).
    ///
    /// @param code SGR colour code
    /// @return CSI sequence
    public static String fg(int code) {
        return CSI + code + "m";
    }

    /// Standard SGR background colour (codes 40-47 normal, 100-107 bright).
    ///
    /// @param code SGR colour code
    /// @return CSI sequence
    public static String bg(int code) {
        return CSI + code + "m";
    }
}
