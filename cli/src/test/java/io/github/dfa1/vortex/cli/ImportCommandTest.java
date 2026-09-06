package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static org.assertj.core.api.Assertions.assertThat;

class ImportCommandTest {

    @Nested
    class ArgParsing {

        @Test
        void noArgs_returnsUsageError() {
            // Given / When
            CliTestSupport.Captured result = capture(() -> ImportCommand.run(new String[]{"import"}));

            // Then
            assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
            assertThat(result.stderr())
                    .contains("usage:")
                    .contains("missing import arguments");
        }

        @Test
        void delimiterMissingValue_returnsUsageError() {
            // Given / When — `--delimiter` at the tail with no value
            CliTestSupport.Captured result = capture(() ->
                    ImportCommand.run(new String[]{"import", "--delimiter"}));

            // Then
            assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
            assertThat(result.stderr()).contains("missing value for --delimiter");
        }

        @Test
        void delimiterMultiCharValue_returnsUsageError() {
            // Given / When — delimiter must be exactly one character
            CliTestSupport.Captured result = capture(() ->
                    ImportCommand.run(new String[]{"import", "--delimiter", "||", "file.csv"}));

            // Then
            assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
            assertThat(result.stderr()).contains("--delimiter must be exactly one character");
        }

        @Test
        void tooManyPositional_returnsUsageError() {
            // Given / When — three positional arguments (only input + optional output allowed)
            CliTestSupport.Captured result = capture(() ->
                    ImportCommand.run(new String[]{"import", "a.csv", "b.vortex", "extra"}));

            // Then
            assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
            assertThat(result.stderr()).contains("input path");
        }

        @Test
        void urlInput_unsupportedExtension_returnsUsageError() {
            // Given / When — only Parquet and CSV sources are supported from a URL; caught
            // before any network call is made, so this needs no mocked HTTP client
            CliTestSupport.Captured result = capture(() ->
                    ImportCommand.run(new String[]{"import", "http://example.com/data.json"}));

            // Then
            assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
            assertThat(result.stderr()).contains("only Parquet or CSV import is supported from a URL");
        }
    }

    @Nested
    class Execution {

        @Test
        void missingInputFile_returnsFileNotFound(@TempDir Path tmp) {
            // Given
            Path missing = tmp.resolve("nope.csv");

            // When
            CliTestSupport.Captured result = capture(() ->
                    ImportCommand.run(new String[]{"import", missing.toString()}));

            // Then
            assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
            assertThat(result.stderr()).contains("file not found");
        }

        @Test
        void validCsv_writesVortexAndReportsResult(@TempDir Path tmp) throws IOException {
            // Given — two-row CSV with a string-typed header inferred by the importer
            Path csv = tmp.resolve("data.csv");
            Files.writeString(csv, "id,value\n1,a\n2,b\n", StandardCharsets.UTF_8);

            // When
            CliTestSupport.Captured result = capture(() ->
                    ImportCommand.run(new String[]{"import", csv.toString()}));

            // Then — output path derives from input filename, command prints the result line
            Path out = tmp.resolve("data.vortex");
            assertThat(result.status()).isEqualTo(ExitStatus.OK);
            assertThat(out).exists();
            assertThat(result.stdout()).contains("written:").contains(out.toString());
        }

        @Test
        void csvWithExplicitOutputPath_usesIt(@TempDir Path tmp) throws IOException {
            // Given
            Path csv = tmp.resolve("in.csv");
            Files.writeString(csv, "id\n1\n2\n", StandardCharsets.UTF_8);
            Path out = tmp.resolve("custom.vortex");

            // When
            CliTestSupport.Captured result = capture(() ->
                    ImportCommand.run(new String[]{"import", csv.toString(), out.toString()}));

            // Then
            assertThat(result.status()).isEqualTo(ExitStatus.OK);
            assertThat(out).exists();
            assertThat(result.stdout()).contains(out.toString());
        }

        @Test
        void csvWithCustomDelimiter_imports(@TempDir Path tmp) throws IOException {
            // Given — tab-separated values
            Path csv = tmp.resolve("data.tsv");
            Files.writeString(csv, "id\tvalue\n1\ta\n2\tb\n", StandardCharsets.UTF_8);

            // When
            CliTestSupport.Captured result = capture(() ->
                    ImportCommand.run(new String[]{"import", "--delimiter", "\t", csv.toString()}));

            // Then — without the explicit delimiter the import would treat the whole row as one
            // column. Success here confirms the `--delimiter` flag plumbs through.
            // Output filename: input is `data.tsv`, deriveOutputPath only strips
            // `.csv`/`.parquet` suffixes, so the result is `data.tsv.vortex`.
            assertThat(result.status()).isEqualTo(ExitStatus.OK);
            assertThat(tmp.resolve("data.tsv.vortex")).exists();
        }

        @Test
        void csvWithParquetOutputPath_chainsThroughTempVortex(@TempDir Path tmp) throws IOException {
            // Given — a `.parquet` destination; Vortex is always the hub, so this chains
            // CSV -> temp Vortex -> Parquet internally and discards the temp file
            Path csv = tmp.resolve("in.csv");
            Files.writeString(csv, "id,name\n1,Ada\n2,Grace\n", StandardCharsets.UTF_8);
            Path out = tmp.resolve("out.parquet");

            // When
            CliTestSupport.Captured result = capture(() ->
                    ImportCommand.run(new String[]{"import", csv.toString(), out.toString()}));

            // Then — the real Parquet output exists; the temp Vortex file (system temp dir, not
            // this directory) leaves nothing behind in the working directory
            assertThat(result.status()).isEqualTo(ExitStatus.OK);
            assertThat(out).exists();
            assertThat(result.stdout()).contains(out.toString());
            try (var files = Files.list(tmp)) {
                assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("in.csv", "out.parquet");
            }
        }

        @Test
        void parquetInputWithParquetOutputPath_returnsUsageError(@TempDir Path tmp) throws IOException {
            // Given — a Parquet source always produces Vortex; a `.parquet`-named output would
            // silently write a Vortex-format file under a misleading name if left unchecked
            Path vortex = CliTestSupport.writeSmallVortex(tmp, "src.vortex");
            Path parquetIn = tmp.resolve("src.parquet");
            io.github.dfa1.vortex.parquet.ParquetExporter.exportParquet(vortex, parquetIn);
            Path parquetOut = tmp.resolve("out.parquet");

            // When
            CliTestSupport.Captured result = capture(() ->
                    ImportCommand.run(new String[]{"import", parquetIn.toString(), parquetOut.toString()}));

            // Then
            assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
            assertThat(result.stderr()).contains("Parquet source cannot import to a .parquet output");
            assertThat(parquetOut).doesNotExist();
        }
    }

    @Nested
    class RestrictToOwner {

        @Test
        void ownerCanStillReadAndWriteAfterRestricting(@TempDir Path tmp) throws IOException {
            // Given
            Path file = Files.createFile(tmp.resolve("scratch.vortex"));

            // When
            Path result = ImportCommand.restrictToOwner(file);

            // Then — this is the non-POSIX equivalent of the POSIX branch's `rw-------`
            // FileAttribute; it must not lock the owner itself out of the file it just created
            assertThat(result).isEqualTo(file);
            assertThat(file.toFile()).canRead();
            assertThat(file.toFile()).canWrite();
        }
    }
}
