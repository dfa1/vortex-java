package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeSmallVortex;
import static org.assertj.core.api.Assertions.assertThat;

/// `main` only calls `System.exit(execute(args))`, so `execute` carries the logic and is tested here: the
/// `--timing` flag. The usage text must advertise every subcommand `execute` dispatches.
class VortexCliTest {

    @Test
    void printUsage_listsEverySubcommand() {
        // Given
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // When
        VortexCli.printUsage(new PrintStream(buf, true, StandardCharsets.UTF_8));

        // Then — one line per subcommand handled by main()'s switch
        String usage = buf.toString(StandardCharsets.UTF_8);
        assertThat(usage)
                .contains("Usage:")
                .contains("inspect")
                .contains("tui")
                .contains("view")
                .contains("export")
                .contains("import")
                .contains("schema")
                .contains("count")
                .contains("select")
                .contains("stats")
                .contains("filter")
                .contains("--timing");
    }

    @Test
    void timing_printsTheElapsedTimeToStderrAndLeavesStdoutAlone(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeSmallVortex(tmp, "t.vortex");

        // When
        CliTestSupport.Captured result = capture(() -> VortexCli.execute(new String[]{"count", file.toString(), "--timing"}));

        // Then — the row count is still the only thing on stdout, so a pipeline sees clean data
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout().strip()).isEqualTo("3");
        assertThat(result.stderr()).matches("elapsed: \\d+\\.\\d ms\\R");
    }

    @Test
    void withoutTiming_printsNothingToStderr(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeSmallVortex(tmp, "t.vortex");

        // When
        CliTestSupport.Captured result = capture(() -> VortexCli.execute(new String[]{"count", file.toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void timing_isAcceptedBeforeTheSubcommand(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeSmallVortex(tmp, "t.vortex");

        // When
        CliTestSupport.Captured result = capture(() -> VortexCli.execute(new String[]{"--timing", "count", file.toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout().strip()).isEqualTo("3");
        assertThat(result.stderr()).contains("elapsed:");
    }

    @Test
    void timing_isPrintedEvenWhenTheCommandFails(@TempDir Path tmp) {
        // Given — a file that does not exist
        Path missing = tmp.resolve("nope.vortex");

        // When
        CliTestSupport.Captured result = capture(() -> VortexCli.execute(new String[]{"count", missing.toString(), "--timing"}));

        // Then — the failure keeps its exit status, and the time is reported all the same
        assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(result.stderr()).contains("file not found").contains("elapsed:");
    }

    @Test
    void timingAlone_isAUsageError() {
        // Given / When — nothing left to run once the flag is removed
        CliTestSupport.Captured result = capture(() -> VortexCli.execute(new String[]{"--timing"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("Usage:");
    }
}
