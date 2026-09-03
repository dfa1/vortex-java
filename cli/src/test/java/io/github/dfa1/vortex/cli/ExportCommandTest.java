package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeSmallVortex;
import static org.assertj.core.api.Assertions.assertThat;

class ExportCommandTest {

    @Test
    void wrongArity_returnsUsageError() {
        // Given / When
        CliTestSupport.Captured result = capture(() -> ExportCommand.run(new String[]{"export"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("usage:");
    }

    @Test
    void missingFile_returnsFileNotFound(@TempDir Path tmp) {
        // Given
        Path missing = tmp.resolve("nope.vortex");

        // When
        CliTestSupport.Captured result = capture(() -> ExportCommand.run(new String[]{"export", missing.toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(result.stderr()).contains("file not found");
    }

    @Test
    void validFile_emitsCsvHeaderAndRows(@TempDir Path tmp) throws IOException {
        // Given — 3-row Vortex file with column "id" = [1, 2, 3]
        Path file = writeSmallVortex(tmp, "export.vortex");

        // When
        CliTestSupport.Captured result = capture(
                () -> ExportCommand.run(new String[]{"export", file.toString(), "-"}));

        // Then — header + 3 data rows
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout()).startsWith("id");
        assertThat(result.stdout().lines().count()).isEqualTo(4);
    }

    @Test
    void defaultOutputPath_writesCsvFileAndPrintsResult(@TempDir Path tmp) throws IOException {
        // Given — no explicit output argument; the default is the input's own name with a
        // `.csv` extension, written as a real file rather than streamed to stdout
        Path file = writeSmallVortex(tmp, "export.vortex");

        // When
        CliTestSupport.Captured result = capture(() -> ExportCommand.run(new String[]{"export", file.toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        Path outputPath = tmp.resolve("export.csv");
        assertThat(outputPath).exists();
        assertThat(result.stdout()).contains("written:").contains("export.csv");
    }

    @Test
    void parquetOutputPath_dispatchesToParquetExport(@TempDir Path tmp) throws IOException {
        // Given — a `.parquet` destination, dispatching away from the CSV default
        Path file = writeSmallVortex(tmp, "export.vortex");
        Path outputPath = tmp.resolve("export.parquet");

        // When
        CliTestSupport.Captured result = capture(
                () -> ExportCommand.run(new String[]{"export", file.toString(), outputPath.toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(outputPath).exists();
        assertThat(result.stdout()).contains("written:").contains("export.parquet");
    }

    // ── URL source: argument validation only — these fail before any network call, so no
    // mocked HTTP client is needed (mirrors ImportCommandTest's equivalent coverage). ──

    @Test
    void urlInput_missingOutputPath_returnsUsageError() {
        // Given / When — a remote source needs an explicit `out.parquet` path
        CliTestSupport.Captured result = capture(
                () -> ExportCommand.run(new String[]{"export", "http://example.com/data.vortex"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("out.parquet");
    }

    @Test
    void urlInput_stdoutRequested_returnsUsageError() {
        // Given / When — stdout streaming from a URL isn't supported
        CliTestSupport.Captured result = capture(
                () -> ExportCommand.run(new String[]{"export", "http://example.com/data.vortex", "-"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
    }

    @Test
    void urlInput_csvOutputRequested_returnsUsageError(@TempDir Path tmp) {
        // Given / When — CSV export from a URL isn't supported, only `.parquet`
        Path outputPath = tmp.resolve("out.csv");
        CliTestSupport.Captured result = capture(() ->
                ExportCommand.run(new String[]{"export", "http://example.com/data.vortex", outputPath.toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
    }
}
