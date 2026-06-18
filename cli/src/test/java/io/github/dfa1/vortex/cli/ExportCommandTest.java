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
}
