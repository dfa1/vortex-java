package io.github.dfa1.vortex.cli.tui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// Exercises the single-thread executor contract without any file I/O: tasks run on the
/// worker thread, `runAndAwait` flushes prior `submit`s in order, a throwing task does not
/// kill the loop, and `close()` stops accepting work.
class IoWorkerTest {

    @Test
    void runAndAwait_runsTaskOnTheWorkerThread() throws InterruptedException {
        // Given
        try (IoWorker sut = new IoWorker("test-io")) {
            AtomicReference<Thread> ranOn = new AtomicReference<>();

            // When
            sut.runAndAwait(() -> ranOn.set(Thread.currentThread()));

            // Then — the task executed off the caller thread, on the named worker
            assertThat(ranOn.get()).isNotNull().isNotSameAs(Thread.currentThread());
            assertThat(ranOn.get().getName()).isEqualTo("test-io");
        }
    }

    @Test
    void pending_isZeroAfterTaskCompletes() throws InterruptedException {
        // Given
        try (IoWorker sut = new IoWorker("test-io")) {
            // When
            sut.runAndAwait(() -> { });

            // Then — the finally block decremented the in-flight counter
            assertThat(sut.pending()).isZero();
        }
    }

    @Test
    void submit_runsTasksInFifoOrder() throws InterruptedException {
        // Given — a blocking runAndAwait at the end flushes the two prior submits
        try (IoWorker sut = new IoWorker("test-io")) {
            List<Integer> order = new CopyOnWriteArrayList<>();

            // When
            sut.submit(() -> order.add(1));
            sut.submit(() -> order.add(2));
            sut.runAndAwait(() -> order.add(3));

            // Then
            assertThat(order).containsExactly(1, 2, 3);
            assertThat(sut.pending()).isZero();
        }
    }

    @Test
    void submit_throwingTask_doesNotKillTheWorker() throws InterruptedException {
        // Given — the loop must swallow a task's RuntimeException and keep draining
        try (IoWorker sut = new IoWorker("test-io")) {
            sut.submit(() -> {
                throw new IllegalStateException("boom");
            });
            AtomicBoolean laterRan = new AtomicBoolean(false);

            // When
            sut.runAndAwait(() -> laterRan.set(true));

            // Then — the subsequent task still executed
            assertThat(laterRan).isTrue();
        }
    }

    @Test
    void submitAfterClose_isDroppedAndDoesNotCount() {
        // Given
        IoWorker sut = new IoWorker("test-io");
        sut.close();
        AtomicBoolean ran = new AtomicBoolean(false);

        // When — submit observes the volatile closed flag and returns early
        sut.submit(() -> ran.set(true));

        // Then — nothing enqueued, nothing counted
        assertThat(sut.pending()).isZero();
        assertThat(ran).isFalse();
    }

    @Test
    void close_isIdempotent() {
        // Given
        IoWorker sut = new IoWorker("test-io");

        // When / Then
        sut.close();
        assertThatCode(sut::close).doesNotThrowAnyException();
    }
}
