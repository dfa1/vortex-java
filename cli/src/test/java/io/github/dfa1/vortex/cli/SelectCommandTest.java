package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeSmallVortex;
import static org.assertj.core.api.Assertions.assertThat;

class SelectCommandTest {

    @Test
    void wrongArity_returnsUsageError() {
        // Given / When — only "select" + file, no column list
        CliTestSupport.Captured result = capture(() -> SelectCommand.run(new String[]{"select", "file"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("usage:");
    }

    @Test
    void missingFile_returnsFileNotFound(@TempDir Path tmp) {
        // Given
        Path missing = tmp.resolve("nope.vortex");

        // When
        CliTestSupport.Captured result = capture(() ->
                SelectCommand.run(new String[]{"select", missing.toString(), "id"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(result.stderr()).contains("file not found");
    }

    @Test
    void validFile_projectsRequestedColumn(@TempDir Path tmp) throws IOException {
        // Given — single I64 column "id" = [1, 2, 3]
        Path file = writeSmallVortex(tmp, "select.vortex");

        // When
        CliTestSupport.Captured result = capture(() ->
                SelectCommand.run(new String[]{"select", file.toString(), "id"}));

        // Then — header is the projected column, 3 data rows follow
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout().lines().findFirst()).hasValue("id");
        assertThat(result.stdout().lines().count()).isEqualTo(4);
    }

    @Test
    void where_filtersRowsOnAPrintedColumn(@TempDir Path tmp) throws IOException {
        // Given — id = 1..6, price = 10.0, 20.0, ... 60.0
        Path file = writeIdAndPrice(tmp);

        // When
        CliTestSupport.Captured result = capture(() ->
                SelectCommand.run(new String[]{"select", file.toString(), "id", "--where", "id >= 4"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout().lines()).containsExactly("id", "4", "5", "6");
    }

    @Test
    void where_filtersOnAColumnThatIsNotPrinted(@TempDir Path tmp) throws IOException {
        // Given — the filter reads `price`, but only `id` is printed: the scan must still read `price`
        Path file = writeIdAndPrice(tmp);

        // When
        CliTestSupport.Captured result = capture(() ->
                SelectCommand.run(new String[]{"select", file.toString(), "id", "--where", "price > 35"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout().lines()).containsExactly("id", "4", "5", "6");
    }

    @Test
    void severalWheres_mustAllHold(@TempDir Path tmp) throws IOException {
        // Given — an interval is two comparisons on the same column
        Path file = writeIdAndPrice(tmp);

        // When
        CliTestSupport.Captured result = capture(() -> SelectCommand.run(new String[]{
                "select", file.toString(), "price", "--where", "id >= 2", "--where", "id < 5"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout().lines()).containsExactly("price", "20.0", "30.0", "40.0");
    }

    @Test
    void whereMatchingNothing_printsOnlyTheHeader(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeIdAndPrice(tmp);

        // When
        CliTestSupport.Captured result = capture(() ->
                SelectCommand.run(new String[]{"select", file.toString(), "id", "--where", "id > 100"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout().lines()).containsExactly("id");
    }

    @Test
    void whereWithoutAValue_returnsUsageError(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeIdAndPrice(tmp);

        // When
        CliTestSupport.Captured result = capture(() ->
                SelectCommand.run(new String[]{"select", file.toString(), "id", "--where"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("usage:").contains("missing value for --where");
    }

    @Test
    void onlyWhere_returnsUsageError(@TempDir Path tmp) throws IOException {
        // Given — a condition but nothing to print
        Path file = writeIdAndPrice(tmp);

        // When
        CliTestSupport.Captured result = capture(() ->
                SelectCommand.run(new String[]{"select", file.toString(), "--where", "id > 1"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("expected at least one column");
    }

    @Test
    void malformedWhere_returnsUsageError(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeIdAndPrice(tmp);

        // When
        CliTestSupport.Captured result = capture(() ->
                SelectCommand.run(new String[]{"select", file.toString(), "id", "--where", "nonsense"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("error:");
    }

    @Test
    void whereOnUnknownColumn_returnsError(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeIdAndPrice(tmp);

        // When
        CliTestSupport.Captured result = capture(() ->
                SelectCommand.run(new String[]{"select", file.toString(), "id", "--where", "missing > 1"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.ERROR);
        assertThat(result.stderr()).contains("error:");
    }

    private static Path writeIdAndPrice(Path dir) throws IOException {
        Path file = dir.resolve("two.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("id"), ColumnName.of("price")),
                List.of(DType.I64, DType.F64),
                false);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(
                    ColumnName.of("id"), new long[]{1, 2, 3, 4, 5, 6},
                    ColumnName.of("price"), new double[]{10.0, 20.0, 30.0, 40.0, 50.0, 60.0}));
        }
        return file;
    }
}
