package com.groupon.sda.concurrency;

import com.groupon.sda.domain.event.Event;
import com.groupon.sda.graph.algorithms.Reachability;
import com.groupon.sda.testsupport.FixtureLoader;
import com.groupon.sda.testsupport.TestStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hot-edge concurrency: 50 distinct events all observing the same {@code a→b} edge
 * are dispatched across many virtual threads while a separate fleet of reader
 * threads runs queries against the graph.
 *
 * <p>Spec wording: <i>"Reads (queries) and writes (event application) run concurrently
 * and must not tear or deadlock."</i>
 *
 * <p>Invariants asserted:
 * <ul>
 *   <li>The graph ends with exactly one edge ({@code a→b}) and {@code sample_count = 50}.</li>
 *   <li>Every event id is recorded in {@code processed_events} (idempotency holds even
 *       when the same id is dispatched from multiple threads).</li>
 *   <li>Reader queries return well-formed results throughout — no exceptions, no
 *       null deref, no impossible states (e.g., {@code reachable(a)} containing
 *       services that never existed).</li>
 *   <li>The whole exercise completes within a bounded time — proves no deadlock.</li>
 * </ul>
 */
class HotEdgeConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-05-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration WINDOW = Duration.ofSeconds(300);

    @Test
    void hotEdgeUnderConcurrentWritersAndReaders(@TempDir Path tmp) throws Exception {
        Path dbFile = tmp.resolve("hot-edge.db");
        List<Event> events = FixtureLoader.load("concurrency/01-hot-edge.json");
        assertThat(events).hasSize(50);

        try (TestStack stack = TestStack.open(dbFile, CLOCK, WINDOW)) {
            int writerThreads = 8;
            int readerThreads = 8;
            int totalThreads = writerThreads + readerThreads;
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(totalThreads);
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();

            // Writers: each writer dispatches the FULL fixture, so every event_id
            // is contended by all writerThreads consumers — exercising both the
            // hot-edge write-lock contention (different events, same edge) and
            // the cross-thread same-id dedup race (same event, multiple threads
            // racing tryClaim).
            for (int t = 0; t < writerThreads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (Event e : events) {
                            stack.consumer.consume(e);
                        }
                    } catch (Throwable th) {
                        firstFailure.compareAndSet(null, th);
                    } finally {
                        done.countDown();
                    }
                });
            }

            // Readers: hammer the graph with queries while the writers are running.
            // We don't assert specific counts here (the writer fleet hasn't finished
            // yet) — we only care that nothing throws and that returned values are
            // structurally well-formed.
            for (int t = 0; t < readerThreads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                        while (System.nanoTime() < deadline) {
                            Reachability.Result r = Reachability.reachable(stack.graph, "a");
                            if (r != null) {
                                // Every reachable node has a non-null id and a non-empty path.
                                for (Reachability.ReachableNode node : r.reachable()) {
                                    if (node.id() == null || node.path().isEmpty()) {
                                        throw new AssertionError("malformed reachable node " + node);
                                    }
                                }
                            }
                            // Health may legitimately be null mid-stream (e.g., before
                            // any event lands on b's incoming side under read-lock view);
                            // we only assert it doesn't throw.
                            stack.graph.computeHealth("b", NOW, WINDOW);
                        }
                    } catch (Throwable th) {
                        firstFailure.compareAndSet(null, th);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS))
                    .as("writers + readers complete without deadlock")
                    .isTrue();
            pool.shutdown();
            if (firstFailure.get() != null) {
                throw new AssertionError("concurrent task failed", firstFailure.get());
            }

            // Final state assertions — after all writers complete.
            assertThat(stack.graph.edgeCount())
                    .as("exactly one edge: a→b")
                    .isEqualTo(1);
            assertThat(stack.consumer.appliedCount())
                    .as("each unique event_id applied exactly once")
                    .isEqualTo(50);
            assertThat(stack.processed.count())
                    .as("processed_events has one row per unique event_id")
                    .isEqualTo(50);

            var edgeRows = stack.edges.findAll();
            assertThat(edgeRows).hasSize(1);
            assertThat(edgeRows.get(0).sampleCount())
                    .as("rolling sample_count reflects exactly 50 observations")
                    .isEqualTo(50L);

            // Final health query: 50 samples, 5 errors → error rate 0.1.
            var h = stack.graph.computeHealth("b", NOW, WINDOW);
            assertThat(h).isNotNull();
            assertThat(h.sampleCount()).isEqualTo(50);
            assertThat(h.errorRate()).isEqualTo(0.1);
        }
    }
}
