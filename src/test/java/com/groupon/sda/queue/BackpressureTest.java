package com.groupon.sda.queue;

import com.groupon.sda.domain.event.DependencyObservedEvent;
import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.domain.event.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backpressure — spec must-have:
 *
 * <blockquote>
 *   "When consumers fall behind, producers must block or shed load deliberately,
 *    not silently drop events."
 * </blockquote>
 *
 * <p>Two behaviours validated:
 * <ol>
 *   <li><b>Block</b> path: a small-capacity queue with a deliberately slow drainer.
 *       A producer issuing many {@code put()}s must block (not drop). All events
 *       eventually land. Total blocked-time observable via metrics.</li>
 *   <li><b>Shed</b> path: same setup but using {@link EventQueue#offer(Event, long, TimeUnit)}
 *       with a short timeout. When the queue stays full, {@code offer} returns
 *       {@code false} and the {@code dropped} counter increments. Documented choice,
 *       counted, never silent.</li>
 * </ol>
 */
class BackpressureTest {

    private static final Instant T0 = Instant.parse("2026-05-10T12:00:00Z");

    private static Event evt(int i) {
        return new DependencyObservedEvent("e-" + i, T0, "a", "b", 1, Status.ok);
    }

    @Test
    void putBlocksProducerWhenConsumerIsSlow_eventuallyAllLand() throws Exception {
        ArrayBlockingQueueAdapter q = new ArrayBlockingQueueAdapter(4);
        int total = 100;
        AtomicLong drained = new AtomicLong();
        CountDownLatch consumerStarted = new CountDownLatch(1);
        AtomicBoolean stopConsumer = new AtomicBoolean(false);

        // Slow consumer: takes one item every ~5ms. With queue capacity 4, the
        // producer will block ~96 times before the consumer catches up.
        Thread consumer = Thread.ofVirtual().start(() -> {
            consumerStarted.countDown();
            try {
                while (!stopConsumer.get() && drained.get() < total) {
                    Event e = q.take();
                    if (e == null) return;   // queue closed and drained
                    drained.incrementAndGet();
                    Thread.sleep(5);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        consumerStarted.await();

        long t0 = System.nanoTime();
        for (int i = 0; i < total; i++) {
            q.put(evt(i));   // BLOCKS when full
        }
        long elapsedNanos = System.nanoTime() - t0;

        // Wait for the consumer to drain — bounded.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (drained.get() < total && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        stopConsumer.set(true);
        q.close();
        consumer.join(2_000);

        // All events landed; nothing was silently dropped.
        QueueMetrics metrics = q.metrics();
        assertThat(drained.get()).isEqualTo(total);
        assertThat(metrics.totalEnqueued()).isEqualTo(total);
        assertThat(metrics.totalDequeued()).isEqualTo(total);
        assertThat(metrics.totalDropped())
                .as("put() must never drop — backpressure is BLOCK")
                .isZero();
        // Producer was blocked for a meaningful amount of time (lower bound:
        // (total - capacity) * consumer-step ≈ 96 * 5ms = 480ms).
        assertThat(metrics.putBlockedNanos())
                .as("put() should have parked while the queue was full")
                .isGreaterThan(0L);
        // And the wall-clock for the put loop should be at least roughly the
        // consumer's drain time, since the producer self-throttles.
        assertThat(elapsedNanos)
                .as("producer self-throttles to consumer rate")
                .isGreaterThan(TimeUnit.MILLISECONDS.toNanos(100));
    }

    @Test
    void offerWithTimeoutShedsAndCountsDropsWhenConsumerNeverDrains() throws Exception {
        ArrayBlockingQueueAdapter q = new ArrayBlockingQueueAdapter(2);

        // Fill the queue to capacity. No consumer is running.
        q.put(evt(0));
        q.put(evt(1));
        assertThat(q.size()).isEqualTo(2);

        // Now offer with a tiny timeout — should return false and increment dropped.
        boolean accepted = q.offer(evt(2), 5, TimeUnit.MILLISECONDS);
        assertThat(accepted).isFalse();

        QueueMetrics metrics = q.metrics();
        assertThat(metrics.totalDropped())
                .as("dropped counter increments on offer-timeout — never silent")
                .isEqualTo(1L);
        assertThat(metrics.totalEnqueued()).isEqualTo(2L);

        // Two more rejected offers — the counter keeps climbing.
        q.offer(evt(3), 5, TimeUnit.MILLISECONDS);
        q.offer(evt(4), 5, TimeUnit.MILLISECONDS);
        assertThat(q.metrics().totalDropped()).isEqualTo(3L);
    }
}
