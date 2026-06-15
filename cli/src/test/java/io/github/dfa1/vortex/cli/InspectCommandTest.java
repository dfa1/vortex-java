package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeSmallVortex;
import static org.assertj.core.api.Assertions.assertThat;

class InspectCommandTest {

    @Test
    void wrongArity_returnsUsageError() {
        // Given / When
        CliTestSupport.Captured got = capture(() -> InspectCommand.run(new String[]{"inspect"}));

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
                InspectCommand.run(new String[]{"inspect", missing.toString()}));

        // Then — open() returns null which the run() loop maps to FILE_NOT_FOUND
        assertThat(got.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(got.stderr()).contains("file not found");
    }

    @Test
    void invalidUrl_returnsFileNotFound() {
        // Given — URI parser rejects whitespace
        // When
        CliTestSupport.Captured got = capture(() ->
                InspectCommand.run(new String[]{"inspect", "http://bad host/x"}));

        // Then — invalid-URL branch returns null too, mapped to FILE_NOT_FOUND
        assertThat(got.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(got.stderr()).contains("invalid URL");
    }

    @Test
    void validFile_printsInspectorReport(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeSmallVortex(tmp, "inspect.vortex");

        // When
        CliTestSupport.Captured got = capture(() ->
                InspectCommand.run(new String[]{"inspect", file.toString()}));

        // Then — the VortexInspector report header is "Vortex v<n>" then schema + layout
        assertThat(got.status()).isEqualTo(ExitStatus.OK);
        assertThat(got.stdout())
                .startsWith("Vortex v")
                .contains("Schema:")
                .contains("Layout:");
    }
}
