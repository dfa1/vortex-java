package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeSmallVortex;
import static org.assertj.core.api.Assertions.assertThat;

class CountCommandTest {

    @Test
    void wrongArity_returnsUsageError(@TempDir Path tmp) {
        // Given / When
        CliTestSupport.Captured got = capture(() -> CountCommand.run(new String[]{"count"}));

        // Then
        assertThat(got.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(got.stderr()).contains("usage:");
    }

    @Test
    void missingFile_returnsFileNotFound(@TempDir Path tmp) {
        // Given
        Path missing = tmp.resolve("nope.vortex");

        // When
        CliTestSupport.Captured got = capture(() -> CountCommand.run(new String[]{"count", missing.toString()}));

        // Then
        assertThat(got.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(got.stderr()).contains("file not found");
    }

    @Test
    void validFile_printsRowCountAndReturnsOk(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeSmallVortex(tmp, "count.vortex");

        // When
        CliTestSupport.Captured got = capture(() -> CountCommand.run(new String[]{"count", file.toString()}));

        // Then
        assertThat(got.status()).isEqualTo(ExitStatus.OK);
        assertThat(got.stdout().trim()).isEqualTo("3");
    }
}
