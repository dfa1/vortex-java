package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeSmallVortex;
import static org.assertj.core.api.Assertions.assertThat;

class SelectCommandTest {

    @Test
    void wrongArity_returnsUsageError() {
        // Given / When — only "select" + file, no column list
        CliTestSupport.Captured got = capture(() -> SelectCommand.run(new String[]{"select", "file"}));

        // Then
        assertThat(got.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(got.stderr()).contains("usage:");
    }

    @Test
    void missingFile_returnsFileNotFound(@TempDir Path tmp) {
        // Given
        Path missing = tmp.resolve("nope.vortex");

        // When
        CliTestSupport.Captured got = capture(() ->
                SelectCommand.run(new String[]{"select", missing.toString(), "id"}));

        // Then
        assertThat(got.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(got.stderr()).contains("file not found");
    }

    @Test
    void validFile_projectsRequestedColumn(@TempDir Path tmp) throws IOException {
        // Given — single I64 column "id" = [1, 2, 3]
        Path file = writeSmallVortex(tmp, "select.vortex");

        // When
        CliTestSupport.Captured got = capture(() ->
                SelectCommand.run(new String[]{"select", file.toString(), "id"}));

        // Then — header is the projected column, 3 data rows follow
        assertThat(got.status()).isEqualTo(ExitStatus.OK);
        assertThat(got.stdout().lines().findFirst()).hasValue("id");
        assertThat(got.stdout().lines().count()).isEqualTo(4);
    }
}
