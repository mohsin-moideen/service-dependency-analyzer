package com.groupon.sda.ingest.consumer;

import com.groupon.sda.domain.event.DependencyObservedEvent;
import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.domain.event.DependencyRemovedEvent;
import com.groupon.sda.domain.event.Event;
import com.groupon.sda.graph.ServiceGraph;
import com.groupon.sda.ingest.dedup.InMemorySetCache;
import com.groupon.sda.ingest.dedup.SeenEventsCache;
import com.groupon.sda.persistence.EdgeRepository;
import com.groupon.sda.persistence.EdgeSampleRepository;
import com.groupon.sda.persistence.PersistenceTestSupport;
import com.groupon.sda.persistence.ProcessedEventsRepository;
import com.groupon.sda.persistence.ServiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency + concurrent correctness for the consumer pipeline. These are spec-
 * required invariants:
 * <blockquote>
 *   "Idempotent processing — duplicate event_ids must not corrupt the graph."<br>
 *   "Tests — Enough automated coverage to convince a reviewer the core invariants
 *   hold: idempotency, concurrent correctness, ..."
 * </blockquote>
 */
class EventConsumerIdempotencyTest {

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private record Stack(EventConsumer consumer, ServiceGraph graph,
                         ProcessedEventsRepository processed, EdgeRepository edges) {
    }

    private static Stack newStack() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        DataSourceTransactionManager txm = new DataSourceTransactionManager(jdbc.getDataSource());
        SeenEventsCache cache = new InMemorySetCache();
        ProcessedEventsRepository processed = new ProcessedEventsRepository(jdbc);
        ServiceRepository services = new ServiceRepository(jdbc);
        EdgeRepository edges = new EdgeRepository(jdbc);
        EdgeSampleRepository samples = new EdgeSampleRepository(jdbc);
        ServiceGraph graph = new ServiceGraph(1024, Duration.ofMinutes(5), CLOCK);
        EventConsumer consumer = new EventConsumer(
                cache, processed, services, edges, samples, graph, CLOCK, txm);
        return new Stack(consumer, graph, processed, edges);
    }

    private static Event observed(String id, String src, String tgt, int latency) {
        return new DependencyObservedEvent(id, T0, src, tgt, latency, Status.ok);
    }

    @Test
    void duplicateEventIdAppliedOnce() {
        Stack s = newStack();
        Event e = observed("e-1", "a", "b", 10);

        s.consumer.consume(e);
        s.consumer.consume(e);   // exact duplicate
        s.consumer.consume(e);   // again

        // Graph: only one edge with sampleCount = 1 (not 3)
        var snap = s.graph.structuralSnapshot();
        assertThat(snap.outgoing().get("a")).containsKey("b");
        assertThat(s.graph.edgeCount()).isEqualTo(1);
        assertThat(s.edges.findAll()).hasSize(1);
        assertThat(s.edges.findAll().get(0).sampleCount()).isEqualTo(1L);

        // Counters: 1 applied, 2 deduped
        assertThat(s.consumer.appliedCount()).isEqualTo(1);
        assertThat(s.consumer.duplicatesCachedCount()).isEqualTo(2);
    }

    @Test
    void concurrentDispatchOfSameIdAppliesExactlyOnce() throws Exception {
        Stack s = newStack();
        Event e = observed("e-shared", "a", "b", 10);

        int threads = 16;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    s.consumer.consume(e);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();

        // Exactly one apply, regardless of how many threads tried.
        assertThat(s.consumer.appliedCount()).isEqualTo(1);
        assertThat(s.graph.edgeCount()).isEqualTo(1);
        assertThat(s.edges.findAll().get(0).sampleCount()).isEqualTo(1L);
        assertThat(s.processed.count()).isEqualTo(1);
    }

    @Test
    void distinctEventsOnSameEdgeAccumulateRollingStats() {
        Stack s = newStack();
        s.consumer.consume(observed("e-1", "a", "b", 10));
        s.consumer.consume(observed("e-2", "a", "b", 30));
        s.consumer.consume(observed("e-3", "a", "b", 20));

        // Three observations: rolling avg = 20.0, count = 3.
        var rows = s.edges.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rollingAvgLatencyMs()).isEqualTo(20.0);
        assertThat(rows.get(0).sampleCount()).isEqualTo(3L);
    }

    @Test
    void nullEventOrNullIdIsDroppedCleanly() {
        Stack s = newStack();
        // null event reference
        s.consumer.consume(null);
        // event with null id
        s.consumer.consume(new DependencyObservedEvent(null, T0, "a", "b", 1, Status.ok));
        // event with blank id
        s.consumer.consume(new DependencyObservedEvent("   ", T0, "a", "b", 1, Status.ok));

        assertThat(s.consumer.appliedCount()).isZero();
        assertThat(s.graph.nodeCount()).isZero();
        assertThat(s.processed.count()).isZero();
    }

    @Test
    void dependencyRemovedForUnknownEdgeIsNoOpAndCounted() {
        Stack s = newStack();
        Event removeGhost = new DependencyRemovedEvent("e-ghost", T0, "ghost-src", "ghost-tgt");
        s.consumer.consume(removeGhost);

        // No node, no edge, but the event was processed (so a re-delivery is deduped).
        assertThat(s.graph.nodeCount()).isZero();
        assertThat(s.graph.droppedRemovalsForUnknownEdges()).isEqualTo(1);
        assertThat(s.processed.exists("e-ghost")).isTrue();
        assertThat(s.consumer.appliedCount()).isEqualTo(1);
    }
}
