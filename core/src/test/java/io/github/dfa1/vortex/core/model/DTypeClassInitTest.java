package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

class DTypeClassInitTest {

    private static final int ROUNDS = 20;

    /// The records, `DType` and the [DTypes] constants, each initialized first by its own thread.
    private static final List<String> CLASSES = List.of(
            "io.github.dfa1.vortex.core.model.DType",
            "io.github.dfa1.vortex.core.model.DTypes",
            "io.github.dfa1.vortex.core.model.DType$Primitive",
            "io.github.dfa1.vortex.core.model.DType$Utf8",
            "io.github.dfa1.vortex.core.model.DType$Bool",
            "io.github.dfa1.vortex.core.model.DType$Binary",
            "io.github.dfa1.vortex.core.model.DType$Null",
            "io.github.dfa1.vortex.core.model.DType$Variant");

    @Test
    void concurrentFirstUse_doesNotDeadlock() throws Exception {
        // Given — a fresh class loader per round, so every class starts uninitialized; when the
        // constants were on DType, a record initialized on one thread (which initializes DType
        // first, for its default methods) while DType's constants (records themselves) were
        // initialized on another waited on each other's class-init lock forever
        URL classes = DType.class.getProtectionDomain().getCodeSource().getLocation();
        for (int round = 0; round < ROUNDS; round++) {
            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes}, ClassLoader.getPlatformClassLoader())) {
                CyclicBarrier start = new CyclicBarrier(CLASSES.size());
                Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
                List<Thread> threads = new ArrayList<>();
                for (String name : CLASSES) {
                    threads.add(Thread.ofPlatform().daemon().start(() -> initialize(name, loader, start, failures)));
                }

                // When
                for (Thread thread : threads) {
                    thread.join(Duration.ofSeconds(10));
                }

                // Then
                assertThat(threads).as("round %d: threads still waiting on a class-init lock", round)
                        .noneMatch(Thread::isAlive);
                // a thread that died on an error is not alive either: without this, a class that
                // fails to load or initialize would pass the check above without being tested
                assertThat(failures).as("round %d: threads that failed to initialize their class", round)
                        .isEmpty();
            }
        }
    }

    private static void initialize(String name, ClassLoader loader, CyclicBarrier start, Queue<Throwable> failures) {
        try {
            start.await();
            Class.forName(name, true, loader);
        } catch (Exception | LinkageError e) {
            failures.add(e);
        }
    }
}
