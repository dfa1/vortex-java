package io.github.dfa1.vortex.cli.tui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class QueryHistoryTest {

    @Test
    void add_appendsAndPersistsAcrossReload(@TempDir Path dir) {
        // Given
        Path file = dir.resolve("hist");
        QueryHistory sut = QueryHistory.load(file);

        // When
        sut.add("select 1");
        sut.add("select 2");

        // Then — a fresh load sees the same entries, oldest first.
        QueryHistory reloaded = QueryHistory.load(file);
        assertThat(reloaded.entries()).containsExactly("select 1", "select 2");
    }

    @Test
    void add_collapsesConsecutiveDuplicates(@TempDir Path dir) {
        // Given
        QueryHistory sut = QueryHistory.load(dir.resolve("hist"));

        // When — same query run twice in a row should not duplicate.
        sut.add("select 1");
        sut.add("select 1");
        sut.add("select 2");
        sut.add("select 1");

        // Then — the second consecutive "select 1" collapses; the later non-consecutive one stays.
        assertThat(sut.entries()).containsExactly("select 1", "select 2", "select 1");
    }

    @Test
    void add_blankQueryIsIgnored(@TempDir Path dir) {
        // Given
        QueryHistory sut = QueryHistory.load(dir.resolve("hist"));

        // When
        sut.add("   \n  ");

        // Then
        assertThat(sut.size()).isZero();
    }

    @Test
    void add_stripsSurroundingWhitespace(@TempDir Path dir) {
        // Given
        QueryHistory sut = QueryHistory.load(dir.resolve("hist"));

        // When
        sut.add("  select 1  ");

        // Then
        assertThat(sut.entries()).containsExactly("select 1");
    }

    @Test
    void add_enforcesMaxEntriesCapDroppingOldest(@TempDir Path dir) {
        // Given
        QueryHistory sut = QueryHistory.load(dir.resolve("hist"));

        // When — push one past the cap; the very first entry must be evicted.
        for (int i = 0; i < QueryHistory.MAX_ENTRIES + 1; i++) {
            sut.add("q" + i);
        }

        // Then
        assertThat(sut.size()).isEqualTo(QueryHistory.MAX_ENTRIES);
        assertThat(sut.entries().getFirst()).isEqualTo("q1");
        assertThat(sut.entries().getLast()).isEqualTo("q" + QueryHistory.MAX_ENTRIES);
    }

    @Test
    void load_multilineQuery_survivesRoundTrip(@TempDir Path dir) {
        // Given — a query with embedded newlines must not split into separate history lines.
        Path file = dir.resolve("hist");
        QueryHistory sut = QueryHistory.load(file);
        String multiline = "select id,\n  name\nfrom vtx.t";

        // When
        sut.add(multiline);

        // Then
        assertThat(QueryHistory.load(file).entries()).containsExactly(multiline);
    }

    @Test
    void load_missingFile_yieldsEmptyHistory(@TempDir Path dir) {
        // Given / When
        QueryHistory sut = QueryHistory.load(dir.resolve("does-not-exist"));

        // Then
        assertThat(sut.entries()).isEmpty();
    }
}
