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
import static io.github.dfa1.vortex.cli.CliTestSupport.writeTypedVortex;
import static org.assertj.core.api.Assertions.assertThat;

class FilterCommandTest {

    @TempDir
    Path tmp;

    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        // 3-row Vortex file with one I64 column `id` = [1, 2, 3].
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
    void doubleValueAgainstLongColumn_returnsOk() {
        // Given — a fractional literal parses as Double, compared against an I64 column
        // (exercises parseValue's Double branch and compareNumeric's Double path)
        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", file.toString(), "id", ">", "1.5"}));

        // Then — id in {2, 3} match
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout()).startsWith("id");
    }

    @Test
    void unknownOperator_returnsUsageError() {
        // Given — a lone '!' is a recognised operator char but not a valid operator
        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", file.toString(), "id", "!", "1"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("unknown operator");
    }

    @Test
    void emptyValue_returnsUsageError() {
        // Given — operator present but no value after it
        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", file.toString(), "id", ">"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("invalid filter expression");
    }

    @Test
    void operatorAtStart_returnsUsageError() {
        // Given — operator at index 0 means an empty column name
        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", file.toString(), "=", "1"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("invalid filter expression");
    }

    @Test
    void invalidColumnName_returnsUsageError() {
        // Given — '-' is not a legal column-name character
        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", file.toString(), "a-b", "=", "1"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("invalid filter expression");
    }

    @Test
    void filtersDoubleColumn_returnsOk() throws IOException {
        // Given — F64 column with a fractional threshold (compareDouble path)
        Path typed = writeTypedVortex(tmp, "typed.vortex");

        // When — price in {200.0, 300.0} match
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", typed.toString(), "price", ">", "150.0"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout()).startsWith("id");
    }

    @Test
    void filtersIntColumn_returnsOk() throws IOException {
        // Given — I32 column (compareValue's IntArray branch)
        Path typed = writeTypedVortex(tmp, "typed.vortex");

        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", typed.toString(), "qty", ">=", "20"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout()).startsWith("id");
    }

    @Test
    void filtersStringColumn_returnsOk() throws IOException {
        // Given — Utf8 column (compareValue's VarBinArray branch, lexicographic compare)
        Path typed = writeTypedVortex(tmp, "typed.vortex");

        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", typed.toString(), "name", "==", "bob"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout()).startsWith("id");
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
