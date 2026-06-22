package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static org.assertj.core.api.Assertions.assertThat;

class ProgressBarTest {

    @ParameterizedTest
    @CsvSource({
            // done, total, pct, hasMarker ('>' present while the bar is not full)
            "0,   100, 0,   true",
            "50,  100, 50,  true",
            "100, 100, 100, false",  // full bar: all '=', no '>'
            "0,   0,   100, false",  // non-positive total reports 100%
    })
    void render_showsPercentAndRowCounts(long done, long total, int pct, boolean hasMarker) {
        // Given / When
        CliTestSupport.Captured result = capture(() -> {
            ProgressBar.render(done, total);
            return 0;
        });

        // Then the line carries the percentage and done/total counts, and a '>' cursor only while
        // the bar is partially filled
        assertThat(result.stderr())
                .contains(String.format("%3d%%  %,d / %,d rows", pct, done, total));
        assertThat(result.stderr().contains(">")).isEqualTo(hasMarker);
    }

    @Test
    void clear_blanksTheLine() {
        // Given / When
        CliTestSupport.Captured result = capture(() -> {
            ProgressBar.clear();
            return 0;
        });

        // Then the carriage-return + 80-space pad overwrites whatever was on the line
        assertThat(result.stderr())
                .startsWith("\r")
                .contains(" ".repeat(80));
    }
}
