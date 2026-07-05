package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.reader.compute.Predicate;
import io.github.dfa1.vortex.csv.RowPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeSmallVortex;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeTypedVortex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        // Given — a lone '!' is a recognized operator char but not a valid operator
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

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({
            "price, >,  150.0", // F64 column — compareDouble path
            "qty,   >=, 20",    // I32 column — compareValue IntArray branch
            "name,  ==, bob"    // Utf8 column — compareValue VarBinArray branch, lexicographic
    })
    void filtersTypedColumn_returnsOk(String column, String op, String value) throws IOException {
        // Given — typed column exercising the per-dtype compare branch
        Path typed = writeTypedVortex(tmp, "typed.vortex");

        // When
        CliTestSupport.Captured result = capture(() ->
                FilterCommand.run(new String[]{"filter", typed.toString(), column, op, value}));

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

    @Test
    void columnPredicate_nullTestLeaves_compileToARowPredicate() {
        // Given the null-test predicate variants — the CLI grammar never produces them, but
        // columnPredicate must handle them for switch exhaustiveness over Predicate
        // When
        RowPredicate isNull = FilterCommand.columnPredicate("c", new Predicate.IsNull());
        RowPredicate isNotNull = FilterCommand.columnPredicate("c", new Predicate.IsNotNull());

        // Then — each compiles to a usable per-row test
        assertThat(isNull).isNotNull();
        assertThat(isNotNull).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("unsupportedCompositePredicates")
    void columnPredicate_compositeAndRangeLeaves_throw(Predicate predicate) {
        // Given a composite or range predicate the CLI cannot express
        // When / Then — columnPredicate rejects it rather than silently mishandling
        assertThatThrownBy(() -> FilterCommand.columnPredicate("c", predicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported on the command line");
    }

    private static Stream<Predicate> unsupportedCompositePredicates() {
        Predicate leaf = new Predicate.Gt(1L);
        return Stream.of(
                new Predicate.Between(1L, 2L),
                new Predicate.And(leaf, leaf),
                new Predicate.Or(leaf, leaf));
    }
}
