package io.github.dfa1.vortex.demo.fakedata;

import java.time.Duration;

/// A single-line, in-place terminal progress bar with an ETA, printed to `System.err` (so it
/// never mixes with a tool's normal `stdout` output). Redraws are throttled to avoid flooding the
/// terminal on fast runs.
final class ProgressBar {

    private static final int BAR_WIDTH = 30;
    private static final Duration MIN_REDRAW_INTERVAL = Duration.ofMillis(100);

    private final long total;
    private final long startNanos;
    private long lastDrawNanos;

    ProgressBar(long total) {
        this.total = total;
        this.startNanos = System.nanoTime();
        // Not Long.MIN_VALUE: `nowNanos - lastDrawNanos` in #update would overflow a signed long
        // on the very first call (a moderate positive nanoTime() value minus the most negative
        // possible long), silently wrapping to a negative duration that always looks "too soon
        // to redraw" -- every non-final update gets throttle-skipped for the rest of the run,
        // since lastDrawNanos then never advances away from MIN_VALUE either. Backdating by one
        // interval instead guarantees the first real call passes the threshold, with no
        // overflow risk since both operands stay close to System.nanoTime()'s own range.
        this.lastDrawNanos = startNanos - MIN_REDRAW_INTERVAL.toNanos();
    }

    /// Redraws the bar for `done` out of the total, unless the minimum redraw interval hasn't
    /// elapsed yet (ignored for the final call, `done == total`, which always draws).
    ///
    /// @param done rows completed so far, in `[0, total]`
    void update(long done) {
        long nowNanos = System.nanoTime();
        boolean isFinal = done >= total;
        if (!isFinal && Duration.ofNanos(nowNanos - lastDrawNanos).compareTo(MIN_REDRAW_INTERVAL) < 0) {
            return;
        }
        lastDrawNanos = nowNanos;

        double fraction = total == 0 ? 1.0 : Math.min(1.0, (double) done / total);
        Duration elapsed = Duration.ofNanos(nowNanos - startNanos);
        Duration eta = estimateRemaining(fraction, elapsed);

        int filled = (int) (fraction * BAR_WIDTH);
        String bar = "=".repeat(filled) + " ".repeat(BAR_WIDTH - filled);
        System.err.printf("\r[%s] %5.1f%%  %,d/%,d rows  elapsed=%s  eta=%s",
                bar, fraction * 100, done, total, format(elapsed), isFinal ? format(Duration.ZERO) : format(eta));
        if (isFinal) {
            System.err.println();
        }
    }

    private static Duration estimateRemaining(double fraction, Duration elapsed) {
        if (fraction <= 0) {
            return Duration.ZERO;
        }
        double totalEstimateNanos = elapsed.toNanos() / fraction;
        return Duration.ofNanos((long) totalEstimateNanos).minus(elapsed);
    }

    private static String format(Duration d) {
        long totalSeconds = Math.max(0, d.toSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? "%dm%02ds".formatted(minutes, seconds) : "%ds".formatted(seconds);
    }
}
