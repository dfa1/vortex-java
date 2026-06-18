package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.VortexHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Unit tests for [LazyGridSource] row decoding: column metadata, windowed reads,
/// single-row access, out-of-range handling, and closed-source rejection. All
/// handle I/O is routed through a real [IoWorker]; the handle is opened on the
/// worker thread to satisfy the reader's confined-arena threading contract.
class LazyGridSourceTest {

    @TempDir
    Path tmp;

    @Test
    void exposesColumnsAndRowCount() throws Exception {
        // Given
        Path file = TuiTestSupport.writeGridVortex(tmp, "src.vortex", 25);

        // When / Then
        withSource(file, source -> {
            assertThat(source.columns()).containsExactly("a", "b", "c");
            assertThat(source.totalRows()).isEqualTo(25L);
            assertThat(source.chunkCount()).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void readsWindowOfRows() throws Exception {
        // Given — columns a/b/c count from 0/100/200, so row r is [r, 100+r, 200+r]
        Path file = TuiTestSupport.writeGridVortex(tmp, "src.vortex", 25);

        // When
        withSource(file, source -> {
            String[][] window = source.readRows(2, 3);

            // Then
            assertThat(window).hasNumberOfRows(3);
            assertThat(window[0]).containsExactly("2", "102", "202");
            assertThat(window[2]).containsExactly("4", "104", "204");
        });
    }

    @Test
    void padsRowsPastEndWithEmptyArrays() throws Exception {
        // Given — only 5 rows, but a window of 4 starting at row 3 overruns the end
        Path file = TuiTestSupport.writeGridVortex(tmp, "src.vortex", 5);

        // When
        withSource(file, source -> {
            String[][] window = source.readRows(3, 4);

            // Then — rows 3,4 present; rows 5,6 are empty (one slot per column)
            assertThat(window).hasNumberOfRows(4);
            assertThat(window[0]).containsExactly("3", "103", "203");
            assertThat(window[2]).hasSize(3).containsOnlyNulls();
        });
    }

    @Test
    void rowReturnsSingleFormattedRow() throws Exception {
        // Given
        Path file = TuiTestSupport.writeGridVortex(tmp, "src.vortex", 10);

        // When
        withSource(file, source -> {
            String[] row = source.row(7);

            // Then
            assertThat(row).containsExactly("7", "107", "207");
        });
    }

    @Test
    void rowOutOfBoundsThrows() throws Exception {
        // Given
        Path file = TuiTestSupport.writeGridVortex(tmp, "src.vortex", 3);

        // When / Then
        withSource(file, source ->
                assertThatThrownBy(() -> source.row(99))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("out of bounds"));
    }

    @Test
    void readRowsAfterCloseThrows() throws Exception {
        // Given
        Path file = TuiTestSupport.writeGridVortex(tmp, "src.vortex", 3);

        // When — close the source, then attempt a read
        try (IoWorker worker = new IoWorker("src-test-io")) {
            VortexHandle handle = TuiTestSupport.openOnWorker(worker, file);
            try {
                LazyGridSource source = LazyGridSource.open(handle, worker);
                source.close();
                source.close(); // idempotent

                // Then
                assertThatThrownBy(() -> source.readRows(0, 1))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("closed");
            } finally {
                TuiTestSupport.closeOnWorker(worker, handle);
            }
        }
    }

    @Test
    void formatsEachColumnType() throws Exception {
        // Given — i64/f64/bool/utf8 columns exercise the formatCell type switch
        Path file = TuiTestSupport.writeMultiTypeVortex(tmp, "typed.vortex");

        // When
        withSource(file, source -> {
            assertThat(source.columns()).containsExactly("i", "d", "flag", "name");
            String[] row = source.row(1);

            // Then — long, double, boolean, utf8 each rendered with its formatter
            assertThat(row).containsExactly("1", "1.5", "false", "b");
        });
    }

    @Test
    void readsAcrossChunkBoundaryThenScrollsBack() throws Exception {
        // Given — 5 rows over 2 chunks; reading the tail then the head forces the
        // scan to be reopened from the start (loadChunk's backward-rewind path)
        Path file = TuiTestSupport.writeMultiTypeVortex(tmp, "typed.vortex");

        // When
        withSource(file, source -> {
            assertThat(source.totalRows()).isEqualTo(5L);
            assertThat(source.chunkCount()).isEqualTo(2);

            // window straddling the chunk boundary (rows 2,3,4 -> chunk0 tail + chunk1)
            String[][] straddle = source.readRows(2, 3);
            assertThat(straddle[0]).containsExactly("2", "2.5", "true", "c");
            assertThat(straddle[1]).containsExactly("3", "3.5", "false", "d");

            // now scroll back to the head -> reopen scan from chunk 0
            String[] first = source.row(0);
            assertThat(first).containsExactly("0", "0.5", "true", "a");
        });
    }

    @FunctionalInterface
    private interface SourceAction {
        void run(LazyGridSource source) throws InterruptedException;
    }

    private static void withSource(Path file, SourceAction action) throws InterruptedException {
        try (IoWorker worker = new IoWorker("src-test-io")) {
            VortexHandle handle = TuiTestSupport.openOnWorker(worker, file);
            try (LazyGridSource source = LazyGridSource.open(handle, worker)) {
                action.run(source);
            } finally {
                TuiTestSupport.closeOnWorker(worker, handle);
            }
        }
    }
}
