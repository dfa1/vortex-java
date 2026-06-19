package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static org.assertj.core.api.Assertions.assertThat;

/// Covers the non-TUI control paths of `view` — the happy path needs a real terminal
/// and is exercised by the TUI tests; here we pin the argument/open failures that return
/// before any terminal is touched.
class ViewCommandTest {

    @Test
    void wrongArity_returnsUsageError() {
        // Given / When — only the subcommand name, no target
        CliTestSupport.Captured result = capture(() -> ViewCommand.run(new String[]{"view"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("usage:");
    }

    @Test
    void missingFile_returnsFileNotFound(@TempDir Path tmp) {
        // Given — a path that does not exist
        Path missing = tmp.resolve("nope.vortex");

        // When
        CliTestSupport.Captured result = capture(() ->
                ViewCommand.run(new String[]{"view", missing.toString()}));

        // Then — open() returns null on a missing local file, run() maps that to FILE_NOT_FOUND
        assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(result.stderr()).contains("file not found");
    }

    @Test
    void malformedUrl_returnsFileNotFound() {
        // Given — http(s) prefix forces URI parsing; the space makes it syntactically invalid
        String badUrl = "http://a b";

        // When
        CliTestSupport.Captured result = capture(() ->
                ViewCommand.run(new String[]{"view", badUrl}));

        // Then — URISyntaxException is caught and reported as a null handle
        assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(result.stderr()).contains("invalid URL");
    }
}
