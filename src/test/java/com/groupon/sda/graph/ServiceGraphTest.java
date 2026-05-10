package com.groupon.sda.graph;

import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.domain.graph.Edge;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceGraphTest {

    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final int MAX_SAMPLES = 4;
    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");
    private static final Clock CLOCK_AT_T0 = Clock.fixed(T0, ZoneOffset.UTC);

    private ServiceGraph newGraph() {
        return new ServiceGraph(MAX_SAMPLES, WINDOW, CLOCK_AT_T0);
    }

    @Test
    void dependencyObservedAddsEdgeAndBothEndpoints() {
        ServiceGraph g = newGraph();
        g.applyDependencyObserved("checkout", "payments", T0, 42, Status.ok);

        assertThat(g.hasNode("checkout")).isTrue();
        assertThat(g.hasNode("payments")).isTrue();
        assertThat(g.nodeCount()).isEqualTo(2);
        assertThat(g.edgeCount()).isEqualTo(1);

        ServiceGraph.Snapshot snap = g.structuralSnapshot();
        assertThat(snap.outgoing().get("checkout")).containsEntry("payments", 42.0);
        assertThat(snap.incoming().get("payments")).containsExactly("checkout");
    }

    @Test
    void rollingAverageIsIncrementalOverObservations() {
        ServiceGraph g = newGraph();
        g.applyDependencyObserved("a", "b", T0,                10, Status.ok);
        g.applyDependencyObserved("a", "b", T0.plusSeconds(1), 20, Status.ok);
        g.applyDependencyObserved("a", "b", T0.plusSeconds(2), 30, Status.ok);

        ServiceGraph.Snapshot snap = g.structuralSnapshot();
        assertThat(snap.outgoing().get("a").get("b")).isEqualTo(20.0);
    }

    @Test
    void dependencyRemovedClearsEdgeAndIncomingSet() {
        ServiceGraph g = newGraph();
        g.applyDependencyObserved("a", "b", T0, 10, Status.ok);
        g.applyDependencyRemoved("a", "b", T0.plusSeconds(1));

        assertThat(g.hasNode("a")).isTrue();
        assertThat(g.hasNode("b")).isTrue();
        assertThat(g.edgeCount()).isZero();

        ServiceGraph.Snapshot snap = g.structuralSnapshot();
        assertThat(snap.outgoing().get("a")).isEmpty();
        assertThat(snap.incoming().get("b")).isEmpty();
    }

    @Test
    void removalOfUnknownEdgeIsCountedNoOp() {
        ServiceGraph g = newGraph();
        g.applyDependencyRemoved("ghost-source", "ghost-target", T0);
        g.applyDependencyRemoved("ghost-source", "ghost-target", T0);

        assertThat(g.droppedRemovalsForUnknownEdges()).isEqualTo(2);
        assertThat(g.nodeCount()).isZero();
        assertThat(g.edgeCount()).isZero();
    }

    @Test
    void removalOfKnownSourceButUnknownEdgeIsAlsoCountedNoOp() {
        ServiceGraph g = newGraph();
        g.applyDependencyObserved("a", "b", T0, 10, Status.ok);
        g.applyDependencyRemoved("a", "c", T0.plusSeconds(1));

        assertThat(g.droppedRemovalsForUnknownEdges()).isEqualTo(1);
        assertThat(g.edgeCount()).isOne();
    }

    @Test
    void serviceMetadataIsPersistedAndPartialUpdatesPreserveOtherFields() {
        ServiceGraph g = newGraph();
        g.applyServiceMetadata("svc", "team-a", "tier-1", "us-east");
        g.applyServiceMetadata("svc", null, "tier-0", null);
        assertThat(g.hasNode("svc")).isTrue();
    }

    @Test
    void heartbeatNewerWinsOlderIgnored() {
        ServiceGraph g = newGraph();
        g.applyHeartbeat("svc", T0.plusSeconds(60));
        g.applyHeartbeat("svc", T0);
        assertThat(g.hasNode("svc")).isTrue();
    }

    @Test
    void sampleDequeIsBoundedBySize() {
        ServiceGraph g = newGraph();   // MAX_SAMPLES = 4
        for (int i = 0; i < 10; i++) {
            g.applyDependencyObserved("a", "b",
                    T0.plusSeconds(i), 10 * (i + 1), Status.ok);
        }

        Edge.HealthSnapshot snap = g.computeHealth("a", T0.plusSeconds(20), WINDOW);
        assertThat(snap).isNotNull();
        assertThat(snap.sampleCount()).isEqualTo(4);
    }

    @Test
    void healthReturnsNullForUnknownService() {
        ServiceGraph g = newGraph();
        assertThat(g.computeHealth("nobody", T0, WINDOW)).isNull();
    }

    @Test
    void healthAggregatesIncomingAndOutgoingEdges() {
        ServiceGraph g = newGraph();
        g.applyDependencyObserved("svc", "downstream", T0, 10, Status.ok);
        g.applyDependencyObserved("upstream", "svc",   T0, 20, Status.error);

        Edge.HealthSnapshot snap = g.computeHealth("svc", T0.plusSeconds(1), WINDOW);
        assertThat(snap).isNotNull();
        assertThat(snap.sampleCount()).isEqualTo(2);
        assertThat(snap.errorRate()).isEqualTo(0.5);
    }

    @Test
    void healthRejectsWindowGreaterThanRetention() {
        // Footgun #5 regression: silently truncating to ageCap is worse than failing.
        ServiceGraph g = newGraph();
        g.applyDependencyObserved("a", "b", T0, 10, Status.ok);

        Duration tooBig = WINDOW.plusSeconds(1);
        assertThatThrownBy(() -> g.computeHealth("a", T0.plusSeconds(1), tooBig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds configured retention");
    }

    @Test
    void healthRejectsZeroOrNegativeWindow() {
        ServiceGraph g = newGraph();
        assertThatThrownBy(() -> g.computeHealth("a", T0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> g.computeHealth("a", T0, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void healthRejectsNullNow() {
        ServiceGraph g = newGraph();
        assertThatThrownBy(() -> g.computeHealth("a", null, WINDOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("now");
    }

    @Test
    void selfLoopIsNotDoubleCountedInHealth() {
        // Bug #8 regression: A->A would otherwise be visited via outgoing AND via
        // incoming, doubling its sample count.
        ServiceGraph g = newGraph();
        g.applyDependencyObserved("svc", "svc", T0, 10, Status.ok);
        g.applyDependencyObserved("svc", "svc", T0.plusSeconds(1), 20, Status.error);

        Edge.HealthSnapshot snap = g.computeHealth("svc", T0.plusSeconds(2), WINDOW);
        assertThat(snap).isNotNull();
        // The discriminator: with the bug, sampleCount would be 4 (each sample counted
        // twice, once via outgoing and once via incoming). With the fix, it's 2.
        assertThat(snap.sampleCount()).isEqualTo(2);
        assertThat(snap.errorRate()).isEqualTo(0.5);
    }

    @Test
    void structuralSnapshotIsIsolatedFromLaterMutations() {
        ServiceGraph g = newGraph();
        g.applyDependencyObserved("a", "b", T0, 10, Status.ok);
        ServiceGraph.Snapshot snap = g.structuralSnapshot();

        g.applyDependencyObserved("a", "c", T0, 20, Status.ok);
        g.applyDependencyRemoved("a", "b", T0.plusSeconds(1));

        assertThat(snap.outgoing().get("a")).containsOnlyKeys("b");
        assertThat(snap.outgoing().get("a")).doesNotContainKey("c");
    }

    @Test
    void structuralSnapshotMapsAreUnmodifiable() {
        // Footgun #4 regression: callers must not be able to mutate snapshot maps.
        ServiceGraph g = newGraph();
        g.applyDependencyObserved("a", "b", T0, 10, Status.ok);
        ServiceGraph.Snapshot snap = g.structuralSnapshot();

        Map<String, Double> outgoingFromA = snap.outgoing().get("a");
        assertThatThrownBy(() -> outgoingFromA.put("c", 99.0))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snap.outgoing().put("ghost", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void concurrentReadsAndWritesDoNotTearOrDeadlock() throws Exception {
        // Exercises every public mutator and every public read under contention.
        // The lock semantics are uniform across all of them, so this should never
        // surface a tear or a deadlock — but if a future change forgets to grab
        // the right lock on a new method, this test catches it.
        ServiceGraph g = newGraph();
        int writers = 4;
        int removers = 2;
        int snapshotReaders = 2;
        int healthReaders = 2;
        int writesPerThread = 500;

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong snapshotReads = new AtomicLong();
        AtomicLong healthReads = new AtomicLong();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();

        // Observers — produce edges over a fixed key space so removers and readers
        // have something to find.
        for (int w = 0; w < writers; w++) {
            final int wid = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        String src = "w" + wid + "-s" + (i % 50);
                        String tgt = "w" + wid + "-t" + (i % 50);
                        g.applyDependencyObserved(src, tgt, T0.plusSeconds(i), i, Status.ok);
                    }
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
                return null;
            });
        }
        // Removers — most calls hit non-existent edges (counted as no-ops); some hit
        // real ones the writers just inserted. Either way, no exceptions allowed.
        for (int r = 0; r < removers; r++) {
            final int rid = r;
            pool.submit(() -> {
                try {
                    start.await();
                    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                    int i = 0;
                    while (System.nanoTime() < deadline) {
                        // Mix targeted removals (edges the writers create) with
                        // ghost removals (must be counted no-ops).
                        int wid = i % 4;
                        int idx = i % 50;
                        // Use a far-future ts so removals dominate writers' observed events.
                        Instant removeTs = T0.plusSeconds(86_400);
                        if ((i & 1) == 0) {
                            g.applyDependencyRemoved("w" + wid + "-s" + idx,
                                    "w" + wid + "-t" + idx, removeTs);
                        } else {
                            g.applyDependencyRemoved("ghost-" + rid + "-" + i,
                                    "ghost-tgt", removeTs);
                        }
                        i++;
                    }
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
                return null;
            });
        }
        // Snapshot readers — exercise the read-lock + iteration path.
        for (int r = 0; r < snapshotReaders; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                    while (System.nanoTime() < deadline) {
                        g.structuralSnapshot();
                        snapshotReads.incrementAndGet();
                    }
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
                return null;
            });
        }
        // Health readers — exercise the read-lock + per-edge sample iteration path.
        for (int r = 0; r < healthReaders; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                    int i = 0;
                    while (System.nanoTime() < deadline) {
                        // Cycle across the writer-key space; many of these will be
                        // unknown services (returns null), some will hit real nodes.
                        String svc = "w" + (i % 4) + "-s" + (i % 50);
                        g.computeHealth(svc, T0.plusSeconds(1000), WINDOW);
                        healthReads.incrementAndGet();
                        i++;
                    }
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
                return null;
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        assertThat(failure.get())
                .as("no thread should have thrown (CME, NPE, deadlock, etc.)")
                .isNull();
        assertThat(snapshotReads.get()).isPositive();
        assertThat(healthReads.get()).isPositive();
    }

    @Test
    void staleObservationAfterRemovalIsRejected() {
        // Mirrors the in-memory side of out-of-order/02 fixture under arrival reorder:
        // remove(T2) lands first, then observed(T1) where T1 < T2 — the observation
        // must be rejected as stale (LWW).
        ServiceGraph g = newGraph();
        g.applyDependencyRemoved("a", "b", T0.plusSeconds(1));
        var stats = g.applyDependencyObserved("a", "b", T0, 10, Status.ok);
        assertThat(stats).as("stale observation should be rejected").isNull();
        assertThat(g.edgeCount()).isZero();
        assertThat(g.droppedStaleObservations()).isEqualTo(1);
    }

    @Test
    void freshObservationAfterRemovalRecreatesEdge() {
        // Same fixture, in-order arrival: remove(T1), then observed(T2 > T1).
        ServiceGraph g = newGraph();
        g.applyDependencyRemoved("a", "b", T0.plusSeconds(1));
        var stats = g.applyDependencyObserved("a", "b", T0.plusSeconds(2), 20, Status.ok);
        assertThat(stats).isNotNull();
        assertThat(stats.sampleCount()).isEqualTo(1L);
        assertThat(g.edgeCount()).isEqualTo(1);
    }

    @Test
    void staleRemovalIsRejectedWhenEdgeHasNewerObservation() {
        // Edge observed at T1+5, then a stale removed event at T1 arrives.
        ServiceGraph g = newGraph();
        g.applyDependencyObserved("a", "b", T0.plusSeconds(5), 10, Status.ok);
        g.applyDependencyRemoved("a", "b", T0);   // stale
        assertThat(g.edgeCount()).as("edge must survive a stale removal").isEqualTo(1);
        assertThat(g.droppedStaleRemovals()).isEqualTo(1);
    }

    @Test
    void rejectsBadConstructorArguments() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceGraph(0, WINDOW, CLOCK_AT_T0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceGraph(MAX_SAMPLES, Duration.ZERO, CLOCK_AT_T0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceGraph(MAX_SAMPLES, null, CLOCK_AT_T0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceGraph(MAX_SAMPLES, WINDOW, null));
    }
}
