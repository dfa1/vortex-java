package io.github.dfa1.vortex.cli.tui;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/// Single-threaded I/O executor that owns one {@link io.github.dfa1.vortex.reader.VortexHandle}.
///
/// Vortex readers use a confined {@link java.lang.foreign.Arena}, so every
/// `slice()` / `scan()` call must happen on the same thread that
/// opened the file. The TUI dispatches all such calls to this worker so the
/// render loop on the main thread never crosses the arena's owning thread.
///
/// {@link #pending()} drives the status-line counter; callers should check it
/// when computing UI state.
public final class IoWorker implements AutoCloseable {

    private final BlockingQueue<Runnable> queue;
    private final AtomicInteger pending;
    private final Thread thread;
    private volatile boolean closed;

    /// Creates and starts the worker thread.
    ///
    /// @param name thread name
    public IoWorker(String name) {
        this.queue = new LinkedBlockingQueue<>();
        this.pending = new AtomicInteger();
        this.thread = new Thread(this::loop, name);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /// Submits a task to run on the worker thread. Returns immediately.
    ///
    /// @param task task that performs I/O and updates shared state
    public void submit(Runnable task) {
        if (closed) {
            return;
        }
        pending.incrementAndGet();
        // Unbounded {@link LinkedBlockingQueue} never rejects on capacity; {@code add} is the
        // right idiom (throws {@code IllegalStateException} on the impossible case) so we don't
        // silently drop tasks if anyone ever swaps in a bounded queue.
        queue.add(() -> {
            try {
                task.run();
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    /// Runs a task on the worker thread and waits for it to complete.
    /// Used at startup to open the handle on the worker's owning thread.
    ///
    /// @param task task to execute
    /// @throws InterruptedException if the calling thread is interrupted while waiting
    public void runAndAwait(Runnable task) throws InterruptedException {
        if (closed) {
            return;
        }
        // Count down only after pending is decremented, so a caller that reads pending()
        // immediately after this returns never observes the in-flight task still counted.
        CountDownLatch done = new CountDownLatch(1);
        pending.incrementAndGet();
        queue.add(() -> {
            try {
                task.run();
            } finally {
                pending.decrementAndGet();
                done.countDown();
            }
        });
        done.await();
    }

    /// Number of submitted tasks that have not yet finished.
    ///
    /// @return pending count, including the currently running task
    public int pending() {
        return pending.get();
    }

    private void loop() {
        while (!closed) {
            Runnable task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                task.run();
            } catch (RuntimeException ignored) {
                // Task is expected to capture its own failures into shared state.
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        thread.interrupt();
    }
}
