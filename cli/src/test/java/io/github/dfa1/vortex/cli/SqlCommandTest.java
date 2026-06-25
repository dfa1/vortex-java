package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SqlCommandTest {

    @Test
    void run_noFileArgument_isUsageError() {
        // Given / When
        CliTestSupport.Captured result = CliTestSupport.capture(() -> SqlCommand.run(new String[]{"sql"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("usage: sql");
    }

    @Test
    void run_missingFile_isFileNotFound(@TempDir Path dir) {
        // Given — a path that does not exist
        String missing = dir.resolve("nope.vortex").toString();

        // When
        CliTestSupport.Captured result =
                CliTestSupport.capture(() -> SqlCommand.run(new String[]{"sql", missing}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(result.stderr()).contains("file not found");
    }

    @Test
    void run_duplicateTableName_isUsageError(@TempDir Path dir) throws IOException {
        // Given — two real files whose filename stems collide (same name, different directories),
        // so both would map to the same SQL table.
        Path a = CliTestSupport.writeSmallVortex(Files.createDirectory(dir.resolve("a")), "t.vortex");
        Path b = CliTestSupport.writeSmallVortex(Files.createDirectory(dir.resolve("b")), "t.vortex");

        // When
        CliTestSupport.Captured result = CliTestSupport.capture(
                () -> SqlCommand.run(new String[]{"sql", a.toString(), b.toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("duplicate table name 't'");
    }

    @Test
    void tableName_stripsDirectoryAndExtension() {
        // Given / When / Then
        assertThat(SqlCommand.tableName(Path.of("/data/ohlc.vortex"))).isEqualTo("ohlc");
        assertThat(SqlCommand.tableName(Path.of("trades.v2.vortex"))).isEqualTo("trades.v2");
        assertThat(SqlCommand.tableName(Path.of("noext"))).isEqualTo("noext");
    }
}
