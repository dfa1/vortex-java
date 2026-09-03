package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileNameTest {

    @ParameterizedTest
    @CsvSource({
            "data.csv,     CSV,     true",
            "data.csv,     PARQUET, false",
            "data.parquet, PARQUET, true",
            "data.vortex,  VORTEX,  true",
    })
    void is_matchesKnownExtension(String name, FileFormat format, boolean expected) {
        // Given
        FileName fileName = new FileName(name);

        // When
        boolean result = fileName.is(format);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "data.csv,     VORTEX, data.vortex",
            "data.parquet, VORTEX, data.vortex",
            "data.vortex,  CSV,    data.csv",
    })
    void withFormat_swapsDifferentKnownExtension(String name, FileFormat target, String expected) {
        // Given
        FileName fileName = new FileName(name);

        // When
        String result = fileName.withFormat(target);

        // Then — the stem is kept, only the trailing known extension changes
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void withFormat_noKnownExtension_appendsTarget() {
        // Given — a name that matches none of CSV/PARQUET/VORTEX (e.g. a .tsv file)
        FileName name = new FileName("data.tsv");

        // When
        String result = name.withFormat(FileFormat.VORTEX);

        // Then — nothing is stripped, the target extension is just appended
        assertThat(result).isEqualTo("data.tsv.vortex");
    }

    @ParameterizedTest
    @CsvSource({
            "data.csv,     CSV",
            "data.parquet, PARQUET",
            "data.vortex,  VORTEX",
    })
    void withFormat_alreadyTargetFormat_appendsRatherThanReturningSameName(String name, FileFormat target) {
        // Given — a name already in the target format. `ImportCommand`/`ExportCommand` derive a
        // default output name this way, e.g. `withFormat(VORTEX)` on an import source that
        // happens to already be named "data.vortex" — the caller must never get its own input
        // name back, or it would open that path for writing while still reading it as the
        // source (regression: an earlier version of #withFormat stripped a known extension
        // whenever the name had one, even when it equaled the target, so "data.vortex" mapped
        // straight back to itself instead of "data.vortex.vortex").
        FileName fileName = new FileName(name);

        // When
        String result = fileName.withFormat(target);

        // Then
        assertThat(result).isNotEqualTo(name);
        assertThat(result).isEqualTo(name + target.extension());
    }

    @Test
    void of_takesPathsFinalComponent() {
        // Given / When
        FileName name = FileName.of(Path.of("a", "b", "data.parquet"));

        // Then
        assertThat(name.value()).isEqualTo("data.parquet");
        assertThat(name.is(FileFormat.PARQUET)).isTrue();
    }
}
