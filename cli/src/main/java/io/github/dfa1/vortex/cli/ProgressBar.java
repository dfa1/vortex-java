package io.github.dfa1.vortex.cli;

/// Renders an in-place (carriage-return overwrite) progress line on stderr, shared by the
/// import/export subcommands. Matches the [io.github.dfa1.vortex.csv.ProgressListener] signature,
/// so it can be passed directly as `ProgressBar::render`.
@SuppressWarnings("java:S106") // progress UI is written to stderr by design
final class ProgressBar {

    private static final int WIDTH = 30;

    private ProgressBar() {
    }

    /// Renders a determinate progress bar: `[====>      ] NN%  done / total rows`. A non-positive
    /// `total` reports 100%.
    ///
    /// @param done  rows processed so far
    /// @param total total rows expected
    static void render(long done, long total) {
        int pct = total > 0 ? (int) (done * 100L / total) : 100;
        int filled = pct * WIDTH / 100;
        String bar = "=".repeat(filled) + (filled < WIDTH ? ">" : "") + " ".repeat(Math.max(0, WIDTH - 1 - filled));
        System.err.printf("\r  [%s] %3d%%  %,d / %,d rows", bar, pct, done, total);
        System.err.flush();
    }

    /// Clears the current progress line.
    static void clear() {
        System.err.printf("\r%-80s\r", "");
        System.err.flush();
    }
}
