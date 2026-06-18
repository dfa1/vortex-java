package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeSmallVortex;
import static org.assertj.core.api.Assertions.assertThat;

class FilterCommandTest {

    @TempDir
    Path tmp;

    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        // 3-row Vortex file with one I64 column {@code id} = [1, 2, 3].
        file = writeSmallVortex(tmp, "filter.vortex");
    }

    @Test
    void wrongArity_returnsUsageError() {
        // Given / When
        CliTestSupport.Captured result = capture(() -> FilterCommand.run(new String[]{"filter"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("usage:");
    }

    @Test
    void missingFile_returnsFileNotFound() {
        // Given
        Path missing = tmp.resolve("nope.vortex");

        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", missing.toString(), "id", ">", "0"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(result.stderr()).contains("file not found");
    }

    @Test
    void invalidExpression_returnsUsageError() {
        // Given — file exists but expression has no operator
        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", file.toString(), "id", "is", "1"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("invalid filter expression");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id > 1", "id >= 2", "id < 3", "id <= 2", "id = 2", "id == 2", "id != 1"})
    void validExpression_returnsOkAndEmitsHeader(String filter) {
        // Given — 3-row file with id in [1, 2, 3] (see setUp)
        String[] parts = filter.split(" ");

        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", file.toString(), parts[0], parts[1], parts[2]}));

        // Then — every accepted operator reaches OK and produces a CSV header line.
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout()).startsWith("id");
    }

    @Test
    void unknownColumn_returnsErrorNotCrash() {
        // Given — parses cleanly, fails at column resolution. CLI must catch VortexException
        // and emit a stable error exit code rather than dumping the stack trace.
        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", file.toString(), "nope", "=", "1"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.ERROR);
        assertThat(result.stderr()).contains("error:");
    }

    @Test
    void operatorPrefixAttack_doesNotCauseBacktracking() {
        // Given — input designed to trigger polynomial backtracking under the previous regex
        // parser (10k-char run before an operator). The current scan-based parser is linear;
        // this test guards against re-introducing a regex with backtracking.
        String longName = "a".repeat(10_000);
        long start = System.nanoTime();

        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", file.toString(), longName, "=", "1"}));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Then — parsing 10k chars must complete well under a second (linear scan).
        // The column won't exist; ERROR exit is expected, USAGE_ERROR is what we rule out.
        assertThat(elapsedMs).isLessThan(1000);
        assertThat(result.status()).isNotEqualTo(ExitStatus.USAGE_ERROR);
    }
}
