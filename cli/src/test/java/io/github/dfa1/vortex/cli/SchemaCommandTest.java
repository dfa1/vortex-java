package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeSmallVortex;
import static org.assertj.core.api.Assertions.assertThat;

class SchemaCommandTest {

    @Test
    void wrongArity_returnsUsageError() {
        // Given / When
        CliTestSupport.Captured got = capture(() -> SchemaCommand.run(new String[]{"schema"}));

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
                SchemaCommand.run(new String[]{"schema", missing.toString()}));

        // Then
        assertThat(got.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(got.stderr()).contains("file not found");
    }

    @Test
    void validFile_printsStructSchemaAndReturnsOk(@TempDir Path tmp) throws IOException {
        // Given — single I64 column named "id" (see CliTestSupport.writeSmallVortex)
        Path file = writeSmallVortex(tmp, "schema.vortex");

        // When
        CliTestSupport.Captured got = capture(() ->
                SchemaCommand.run(new String[]{"schema", file.toString()}));

        // Then — header + per-column row; row count and column count surfaced
        assertThat(got.status()).isEqualTo(ExitStatus.OK);
        assertThat(got.stdout())
                .contains("schema.vortex")
                .contains("3 rows")
                .contains("1 columns")
                .contains("1  id")
                .contains("I64");
    }
}
