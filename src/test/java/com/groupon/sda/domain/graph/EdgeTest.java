package com.groupon.sda.domain.graph;

import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeTest {

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");
    private static final Clock CLOCK_AT_T0 = Clock.fixed(T0, ZoneOffset.UTC);

    @Test
    void recordObservationUpdatesRollingAverageAndCount() {
        Edge e = new Edge("a", "b", 8, Duration.ofMinutes(5), CLOCK_AT_T0);
        e.recordObservation(T0,                  10, Status.ok);
        e.recordObservation(T0.plusSeconds(1),   30, Status.ok);
        e.recordObservation(T0.plusSeconds(2),   20, Status.ok);

        assertThat(e.sampleCount()).isEqualTo(3);
        assertThat(e.rollingAvgLatencyMs()).isEqualTo(20.0);
    }

    @Test
    void recordObservationEvictsBySize() {
        Edge e = new Edge("a", "b", 3, Duration.ofMinutes(5), CLOCK_AT_T0);
        for (int i = 0; i < 6; i++) {
            e.recordObservation(T0.plusSeconds(i), i, Status.ok);
        }
        long retained = 0;
        for (Sample s : e.recentSamplesView()) retained++;
        assertThat(retained).isEqualTo(3);
    }

    @Test
    void recordObservationEvictsByAgeWhenWallClockAdvances() {
        // Bug #3 regression: eviction reference is wall clock, not event ts.
        // Even if the events themselves are timestamped recently, advancing the wall
        // clock past ageCap triggers eviction on the next insert.
        MutableClock clock = new MutableClock(T0);
        Edge e = new Edge("a", "b", 1024, Duration.ofSeconds(60), clock);

        e.recordObservation(T0,                 10, Status.ok);
        e.recordObservation(T0.plusSeconds(30), 20, Status.ok);
        // No eviction yet — clock is at T0.
        assertThat(countSamples(e)).isEqualTo(2);

        // Advance the wall clock; cutoff = (T0+90s) - 60s = T0+30s.
        clock.setInstant(T0.plusSeconds(90));
        // The next insert triggers eviction. The first sample (ts=T0) is older than
        // T0+30s and gets evicted.
        e.recordObservation(T0.plusSeconds(90), 30, Status.ok);

        // Retained: T0+30s, T0+90s. Evicted: T0.
        assertThat(countSamples(e)).isEqualTo(2);
    }

    @Test
    void evictionFullScanCatchesOutOfOrderStaleSamples() {
        // Bug #2 regression. With insertion-ordered deque and out-of-order events:
        //   insert ts=0, then ts=30, then ts=15 — deque is [0, 30, 15].
        // A buggy "peek front and bail" eviction with cutoff=20 would evict the 0,
        // see 30 >= 20 and stop, leaving 15 (which is older than cutoff) behind.
        // The full-scan implementation must catch all three correctly.
        MutableClock clock = new MutableClock(T0);
        Edge e = new Edge("a", "b", 1024, Duration.ofSeconds(60), clock);

        e.recordObservation(T0,                  10, Status.ok);   // ts=0s
        e.recordObservation(T0.plusSeconds(30),  20, Status.ok);   // ts=30s
        e.recordObservation(T0.plusSeconds(15),  15, Status.ok);   // ts=15s, OUT OF ORDER
        // Deque insertion order is [ts=0, ts=30, ts=15].
        assertThat(countSamples(e)).isEqualTo(3);

        // Advance wall clock so cutoff = T0+80s - 60s = T0+20s.
        clock.setInstant(T0.plusSeconds(80));
        // Trigger eviction with another insert.
        e.recordObservation(T0.plusSeconds(80), 80, Status.ok);

        // Survivors: ts=30s, ts=80s. Evicted: ts=0, ts=15. (Buggy front-truncate would
        // leave ts=15 behind.)
        assertThat(countSamples(e)).isEqualTo(2);
    }

    @Test
    void computeHealthIsReadOnly_concurrentCallersDoNotTear() throws Exception {
        // Regression for: Edge.computeHealth used to call evictOlderThan, which mutates
        // the sample deque. Two threads holding the graph's read lock could race and
        // throw ConcurrentModificationException. After the fix, computeHealth filters
        // by timestamp without mutating.
        Edge e = new Edge("a", "b", 1024, Duration.ofMinutes(5), CLOCK_AT_T0);
        for (int i = 0; i < 100; i++) {
            e.recordObservation(T0.minusSeconds(600 + i), i, Status.ok);   // ancient
        }
        for (int i = 0; i < 100; i++) {
            e.recordObservation(T0.plusSeconds(i), 100 + i, Status.ok);    // in window
        }

        int readerThreads = 8;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int t = 0; t < readerThreads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                    while (System.nanoTime() < deadline) {
                        Edge.HealthSnapshot snap = e.computeHealth(
                                T0.plusSeconds(200), Duration.ofMinutes(5));
                        if (snap == null || snap.sampleCount() == 0) {
                            failure.compareAndSet(null,
                                    new AssertionError("expected non-empty snapshot"));
                            return;
                        }
                    }
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get())
                .as("no thread should have thrown (CME, NPE, etc.)")
                .isNull();
    }

    @Test
    void computeHealthReturnsNullWhenNoSamplesInWindow() {
        Edge e = new Edge("a", "b", 8, Duration.ofMinutes(5), CLOCK_AT_T0);
        e.recordObservation(T0, 10, Status.ok);

        Edge.HealthSnapshot snap = e.computeHealth(T0.plusSeconds(3_600), Duration.ofMinutes(5));
        assertThat(snap).isNull();
    }

    @Test
    void computeHealthErrorRateAndP95() {
        Edge e = new Edge("a", "b", 100, Duration.ofMinutes(5), CLOCK_AT_T0);
        for (int i = 0; i < 9; i++) {
            e.recordObservation(T0.plusSeconds(i), 10 + i, Status.ok);
        }
        e.recordObservation(T0.plusSeconds(9), 100, Status.error);

        Edge.HealthSnapshot snap = e.computeHealth(T0.plusSeconds(10), Duration.ofMinutes(5));
        assertThat(snap).isNotNull();
        assertThat(snap.sampleCount()).isEqualTo(10);
        assertThat(snap.errorRate()).isEqualTo(0.1);
        assertThat(snap.p95LatencyMs()).isEqualTo(100);
    }

    @Test
    void recentSamplesViewIsUnmodifiable() {
        Edge e = new Edge("a", "b", 8, Duration.ofMinutes(5), CLOCK_AT_T0);
        e.recordObservation(T0, 10, Status.ok);

        Iterable<Sample> view = e.recentSamplesView();
        // The view is a Collection wrapped in unmodifiable; casting and trying to add
        // must throw.
        assertThatThrownBy(() -> {
            @SuppressWarnings("unchecked")
            java.util.Collection<Sample> asColl = (java.util.Collection<Sample>) view;
            asColl.add(new Sample(T0, 1, Status.ok));
        }).isInstanceOf(UnsupportedOperationException.class);
    }

    private static long countSamples(Edge e) {
        long n = 0;
        for (Sample s : e.recentSamplesView()) n++;
        return n;
    }
}
