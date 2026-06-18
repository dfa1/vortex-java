package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeSmallVortex;
import static org.assertj.core.api.Assertions.assertThat;

class StatsCommandTest {

    @Test
    void wrongArity_returnsUsageError() {
        // Given / When
        CliTestSupport.Captured result = capture(() -> StatsCommand.run(new String[]{"stats"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("usage:");
    }

    @Test
    void missingFile_returnsFileNotFound(@TempDir Path tmp) {
        // Given
        Path missing = tmp.resolve("nope.vortex");

        // When
        CliTestSupport.Captured result = capture(() -> StatsCommand.run(new String[]{"stats", missing.toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(result.stderr()).contains("file not found");
    }

    @Test
    void validFile_printsRowCountAndColumnTable(@TempDir Path tmp) throws IOException {
        // Given — 3-row file with column "id" of type I64
        Path file = writeSmallVortex(tmp, "stats.vortex");

        // When
        CliTestSupport.Captured result = capture(() -> StatsCommand.run(new String[]{"stats", file.toString()}));

        // Then — header line + column row with the type and observed min/max
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout())
                .contains("rows: 3")
                .contains("column")
                .contains("id")
                .contains("i64");
    }
}
