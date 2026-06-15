package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class CliIT {

    private static String captureStdout(IntSupplier action) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        try {
            action.getAsInt();
        } finally {
            System.setOut(saved);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static String captureStderr(IntSupplier action) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream saved = System.err;
        System.setErr(new PrintStream(baos, true, StandardCharsets.UTF_8));
        try {
            action.getAsInt();
        } finally {
            System.setErr(saved);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    @Test
    void fullPipeline(@TempDir Path tmp) throws Exception {
        // Given
        Path csvIn = tmp.resolve("data.csv");
        Files.writeString(csvIn, "id,name\n1,Alice\n2,Bob\n");

        // Step 1: import CSV → vortex
        assertThat(ImportCommand.run(new String[]{"import", csvIn.toString()})).isZero();
        Path vortex = tmp.resolve("data.vortex");
        assertThat(vortex).exists();

        // Step 2: inspect
        String inspect = captureStdout(
                () -> InspectCommand.run(new String[]{"inspect", vortex.toString()}));
        assertThat(inspect).contains("Schema:").contains("id").contains("name");

        // Step 3: schema
        String schema = captureStdout(
                () -> SchemaCommand.run(new String[]{"schema", vortex.toString()}));
        assertThat(schema.strip()).isEqualTo("struct<id: I64, name: utf8>");

        // Step 4: export vortex → CSV
        String exported = captureStdout(
                () -> ExportCommand.run(new String[]{"export", vortex.toString()}));
        String[] exportedLines = exported.split("\r?\n");
        assertThat(exportedLines).hasSize(3);
        assertThat(exportedLines[0]).isEqualTo("id,name");
        assertThat(exportedLines[1]).isEqualTo("1,Alice");
        assertThat(exportedLines[2]).isEqualTo("2,Bob");

        // Step 5: import the exported CSV again and verify schema is preserved
        Path csvIn2 = tmp.resolve("data2.csv");
        Files.writeString(csvIn2, exported);
        assertThat(ImportCommand.run(new String[]{"import", csvIn2.toString()})).isZero();
        Path vortex2 = tmp.resolve("data2.vortex");
        String schema2 = captureStdout(
                () -> SchemaCommand.run(new String[]{"schema", vortex2.toString()}));
        assertThat(schema2.strip()).isEqualTo(schema.strip());
    }

    @Test
    void importExportRoundTrip(@TempDir Path tmp) throws Exception {
        // Given
        Path csvIn = tmp.resolve("data.csv");
        Files.writeString(csvIn, "id,name\n1,Alice\n2,Bob\n3,Charlie\n");

        // When
        assertThat(ImportCommand.run(new String[]{"import", csvIn.toString()})).isZero();
        Path vortexPath = tmp.resolve("data.vortex");
        String exported = captureStdout(
                () -> ExportCommand.run(new String[]{"export", vortexPath.toString()}));

        // Then
        String[] lines = exported.split("\r?\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).isEqualTo("id,name");
        assertThat(lines[1]).isEqualTo("1,Alice");
        assertThat(lines[2]).isEqualTo("2,Bob");
        assertThat(lines[3]).isEqualTo("3,Charlie");
    }

    @Test
    void inspectPrintsSchema(@TempDir Path tmp) throws Exception {
        // Given
        Path csvIn = tmp.resolve("data.csv");
        Files.writeString(csvIn, "id,score\n1,9.5\n");
        ImportCommand.run(new String[]{"import", csvIn.toString()});
        Path vortexPath = tmp.resolve("data.vortex");

        // When
        String output = captureStdout(
                () -> InspectCommand.run(new String[]{"inspect", vortexPath.toString()}));

        // Then
        assertThat(output).contains("Schema:").contains("id").contains("score");
    }

    @Test
    void schemaPrintsMachineReadableDType(@TempDir Path tmp) throws Exception {
        // Given
        Path csvIn = tmp.resolve("data.csv");
        Files.writeString(csvIn, "id,name\n1,Alice\n");
        ImportCommand.run(new String[]{"import", csvIn.toString()});
        Path vortexPath = tmp.resolve("data.vortex");

        // When
        String output = captureStdout(
                () -> SchemaCommand.run(new String[]{"schema", vortexPath.toString()}));

        // Then
        assertThat(output.strip()).isEqualTo("struct<id: I64, name: utf8>");
    }

    @Test
    void importReportsLargerWhenVortexExceedsCsv(@TempDir Path tmp) throws Exception {
        // Given — tiny CSV produces vortex overhead > input size
        Path csvIn = tmp.resolve("data.csv");
        Files.writeString(csvIn, "id,name\n1,Alice\n2,Bob\n");

        // When
        String output = captureStdout(
                () -> ImportCommand.run(new String[]{"import", csvIn.toString()}));

        // Then — must say "larger", never a negative "smaller"
        assertThat(output).contains("larger").doesNotContain("smaller");
    }

    @Test
    void importParquetPipeline(@TempDir Path tmp) throws Exception {
        // Given — test.parquet: id (int64), name (string), 3 rows
        Path parquetIn = tmp.resolve("test.parquet");
        Files.copy(
                Objects.requireNonNull(CliIT.class.getResourceAsStream("/test.parquet")),
                parquetIn);

        // When
        assertThat(ImportCommand.run(new String[]{"import", parquetIn.toString()})).isZero();
        Path vortex = tmp.resolve("test.vortex");
        assertThat(vortex).exists();

        // Then — schema and row count correct
        String schema = captureStdout(
                () -> SchemaCommand.run(new String[]{"schema", vortex.toString()}));
        assertThat(schema.strip()).isEqualTo("struct<id: I64, name: utf8>");

        String count = captureStdout(
                () -> CountCommand.run(new String[]{"count", vortex.toString()}));
        assertThat(count.strip()).isEqualTo("3");
    }

    @Test
    void importAcceptsCustomDelimiter(@TempDir Path tmp) throws Exception {
        // Given
        Path csvIn = tmp.resolve("data.csv");
        Files.writeString(csvIn, "id;name\n1;Alice\n2;Bob\n");

        // When
        assertThat(ImportCommand.run(new String[]{"import", "--delimiter", ";", csvIn.toString()})).isZero();
        Path vortex = tmp.resolve("data.vortex");
        String exported = captureStdout(
                () -> ExportCommand.run(new String[]{"export", vortex.toString()}));

        // Then
        String[] lines = exported.split("\r?\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("id,name");
        assertThat(lines[1]).isEqualTo("1,Alice");
        assertThat(lines[2]).isEqualTo("2,Bob");
    }

    @Test
    void importRejectsMultiCharacterDelimiter(@TempDir Path tmp) throws Exception {
        // Given
        Path csvIn = tmp.resolve("data.csv");
        Files.writeString(csvIn, "id,name\n1,Alice\n");

        // When
        String stderr = captureStderr(
                () -> ImportCommand.run(new String[]{"import", "--delimiter", "::", csvIn.toString()}));

        // Then
        assertThat(stderr).contains("--delimiter must be exactly one character");
        assertThat(stderr).contains("usage: import [--delimiter <char>]");
        assertThat(tmp.resolve("data.vortex")).doesNotExist();
    }
}
