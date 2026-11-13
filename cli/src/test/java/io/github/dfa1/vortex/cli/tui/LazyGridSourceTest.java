package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.VortexHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/// Unit tests for [LazyGridSource] row decoding: column metadata, non-blocking windowed reads,
/// out-of-range handling, superseded and failed loads, and closed-source rejection. All
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
        withSource(file, (source, _) -> {
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
        withSource(file, (source, worker) -> {
            String[][] window = read(source, worker, 2, 3);

            // Then
            assertThat(window).hasNumberOfRows(3);
            assertThat(window[0]).containsExactly("2", "102", "202");
            assertThat(window[2]).containsExactly("4", "104", "204");
        });
    }

    @Test
    void peekRowsReturnsPlaceholdersThenLoadedRowsWithoutBlocking() throws Exception {
        // Given — nothing cached yet, so the first peek must not wait for the worker
        Path file = TuiTestSupport.writeGridVortex(tmp, "src.vortex", 25);

        withSource(file, (source, worker) -> {
            // When
            LazyGridSource.Rows first = source.peekRows(2, 3, 0, 3);
            awaitWorker(worker);
            LazyGridSource.Rows result = source.peekRows(2, 3, 0, 3);

            // Then — placeholders now, real rows once the worker has delivered
            assertThat(first.complete()).isFalse();
            assertThat(first.rows()[0]).containsExactly("…", "…", "…");
            assertThat(result.complete()).isTrue();
            assertThat(result.rows()[0]).containsExactly("2", "102", "202");
            assertThat(result.rows()[2]).containsExactly("4", "104", "204");
        });
    }

    @Test
    void peekRowsSkipsAWindowSupersededBeforeTheWorkerReachesIt() throws Exception {
        // Given — the worker is busy, so window A is still queued when the user scrolls on to B
        Path file = TuiTestSupport.writeGridVortex(tmp, "src.vortex", 25);

        withSource(file, (source, worker) -> {
            CountDownLatch release = blockWorker(worker);
            source.peekRows(0, 3, 0, 3);
            source.peekRows(10, 3, 0, 3);

            // When
            release.countDown();
            awaitWorker(worker);

            // Then — B was loaded and A never was: loading A would only have cost round trips
            assertThat(source.peekRows(10, 3, 0, 3).complete()).isTrue();
            assertThat(source.peekRows(0, 3, 0, 3).complete()).isFalse();
        });
    }

    @Test
    void peekRowsReportsColumnsOutsideTheLoadedRangeAsIncomplete() throws Exception {
        // Given — 4 columns; asking for the first one loads it plus a margin of two
        Path file = TuiTestSupport.writeMultiTypeVortex(tmp, "typed.vortex");

        withSource(file, (source, worker) -> {
            source.peekRows(0, 3, 0, 1);
            awaitWorker(worker);

            // When
            LazyGridSource.Rows inside = source.peekRows(0, 3, 0, 1);
            LazyGridSource.Rows outside = source.peekRows(0, 3, 3, 4);

            // Then
            assertThat(inside.complete()).isTrue();
            assertThat(inside.rows()[0][0]).isEqualTo("0");
            assertThat(outside.complete()).isFalse();
            assertThat(outside.rows()[0]).containsOnly("…");
        });
    }

    @Test
    void peekRowsFailureBelongsToItsWindow() throws Exception {
        // Given — a handle closed under the source, so every load fails
        Path file = TuiTestSupport.writeGridVortex(tmp, "src.vortex", 25);
        try (IoWorker worker = new IoWorker("src-test-io")) {
            VortexHandle handle = TuiTestSupport.openOnWorker(worker, file);
            try (LazyGridSource source = LazyGridSource.open(handle, worker)) {
                TuiTestSupport.closeOnWorker(worker, handle);
                source.peekRows(0, 3, 0, 3);
                awaitWorker(worker);

                // When / Then — that window reports the failure, another one does not inherit it
                assertThatThrownBy(() -> source.peekRows(0, 3, 0, 3)).isInstanceOf(RuntimeException.class);
                assertThat(source.peekRows(10, 3, 0, 3).complete()).isFalse();
            }
        }
    }

    @Test
    void closeDoesNotWaitForALoadStuckOnTheWorker() throws Exception {
        // Given — a load stalled behind a busy worker, as on a hung HTTP request
        Path file = TuiTestSupport.writeGridVortex(tmp, "src.vortex", 25);
        try (IoWorker worker = new IoWorker("src-test-io")) {
            VortexHandle handle = TuiTestSupport.openOnWorker(worker, file);
            LazyGridSource source = LazyGridSource.open(handle, worker);
            CountDownLatch release = blockWorker(worker);
            source.peekRows(0, 3, 0, 3);

            // When / Then — close gives up after the teardown wait instead of hanging
            assertTimeoutPreemptively(Duration.ofSeconds(5), source::close);
            release.countDown();
        }
    }

    @Test
    void padsRowsPastEndWithEmptyArrays() throws Exception {
        // Given — only 5 rows, but a window of 4 starting at row 3 overruns the end
        Path file = TuiTestSupport.writeGridVortex(tmp, "src.vortex", 5);

        // When
        withSource(file, (source, worker) -> {
            String[][] window = read(source, worker, 3, 4);

            // Then — rows 3,4 present; rows 5,6 are empty (one slot per column)
            assertThat(window).hasNumberOfRows(4);
            assertThat(window[0]).containsExactly("3", "103", "203");
            assertThat(window[2]).hasSize(3).containsOnlyNulls();
        });
    }

    @Test
    void peekRowsAfterCloseThrows() throws Exception {
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
                assertThatThrownBy(() -> source.peekRows(0, 1, 0, 1))
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
        withSource(file, (source, worker) -> {
            assertThat(source.columns()).containsExactly("i", "d", "flag", "name");
            String[] row = read(source, worker, 1, 1)[0];

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
        withSource(file, (source, worker) -> {
            assertThat(source.totalRows()).isEqualTo(5L);
            assertThat(source.chunkCount()).isEqualTo(2);

            // window straddling the chunk boundary (rows 2,3,4 -> chunk0 tail + chunk1)
            String[][] straddle = read(source, worker, 2, 3);
            assertThat(straddle[0]).containsExactly("2", "2.5", "true", "c");
            assertThat(straddle[1]).containsExactly("3", "3.5", "false", "d");

            // now scroll back to the head -> reopen scan from chunk 0
            String[] first = read(source, worker, 0, 1)[0];
            assertThat(first).containsExactly("0", "0.5", "true", "a");
        });
    }

    @FunctionalInterface
    private interface WorkerAction {
        void run(LazyGridSource source, IoWorker worker) throws InterruptedException;
    }

    /// All columns of the window, once the worker has loaded it.
    private static String[][] read(LazyGridSource source, IoWorker worker, long start, int count)
            throws InterruptedException {
        int columns = source.columns().size();
        source.peekRows(start, count, 0, columns);
        awaitWorker(worker);
        return source.peekRows(start, count, 0, columns).rows();
    }

    private static void withSource(Path file, WorkerAction action) throws InterruptedException {
        try (IoWorker worker = new IoWorker("src-test-io")) {
            VortexHandle handle = TuiTestSupport.openOnWorker(worker, file);
            try (LazyGridSource source = LazyGridSource.open(handle, worker)) {
                action.run(source, worker);
            } finally {
                TuiTestSupport.closeOnWorker(worker, handle);
            }
        }
    }

    /// The worker runs tasks in order, so once this returns every task submitted before it is done.
    private static void awaitWorker(IoWorker worker) throws InterruptedException {
        worker.runAndAwait(() -> { });
    }

    /// Occupies the worker until the returned latch is released.
    private static CountDownLatch blockWorker(IoWorker worker) {
        CountDownLatch release = new CountDownLatch(1);
        worker.submit(() -> {
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        return release;
    }
}
