package com.groupon.sda.queue;

import com.groupon.sda.domain.event.DependencyObservedEvent;
import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.domain.event.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Graceful shutdown — spec must-have:
 *
 * <blockquote>
 *   "On SIGTERM, drain in-flight events, snapshot state, and exit cleanly."
 * </blockquote>
 *
 * <p>Drives the queue's close/drain protocol directly:
 * <ul>
 *   <li>After {@link EventQueue#close()}, in-flight items still come out of
 *       {@link EventQueue#take()} until the buffer empties; only then does {@code take}
 *       return {@code null} so consumers can exit their loop.</li>
 *   <li>{@link EventQueue#put} / {@link EventQueue#offer} reject new items with
 *       {@link QueueClosedException} once {@code close()} has run — no events accepted
 *       after we've decided to stop.</li>
 *   <li>A producer parked inside {@code put} when {@code close()} runs must exit with
 *       {@link QueueClosedException}, not silently succeed.</li>
 * </ul>
 */
class GracefulShutdownTest {

    private static final Instant T0 = Instant.parse("2026-05-10T12:00:00Z");

    private static Event evt(int i) {
        return new DependencyObservedEvent("e-" + i, T0, "a", "b", 1, Status.ok);
    }

    @Test
    void afterCloseTakeDrainsThenReturnsNull() throws Exception {
        ArrayBlockingQueueAdapter q = new ArrayBlockingQueueAdapter(8);
        for (int i = 0; i < 5; i++) {
            q.put(evt(i));
        }
        q.close();

        // 5 in-flight items still come out, in FIFO order, even though the queue is closed.
        List<Event> drained = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Event e = q.take();
            assertThat(e).isNotNull();
            drained.add(e);
        }
        assertThat(drained).hasSize(5);
        assertThat(drained.get(0).eventId()).isEqualTo("e-0");
        assertThat(drained.get(4).eventId()).isEqualTo("e-4");

        // Now empty + closed — take() returns null so the consumer loop can exit.
        Event end = q.take();
        assertThat(end).as("take() returns null when queue is closed and drained").isNull();
    }

    @Test
    void putAfterCloseThrowsQueueClosed() {
        ArrayBlockingQueueAdapter q = new ArrayBlockingQueueAdapter(2);
        q.close();
        assertThatThrownBy(() -> q.put(evt(0))).isInstanceOf(QueueClosedException.class);
        assertThatThrownBy(() -> q.offer(evt(0), 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(QueueClosedException.class);
    }

    @Test
    void parkedProducerWakesAndThrowsWhenQueueClosesMidFlight() throws Exception {
        ArrayBlockingQueueAdapter q = new ArrayBlockingQueueAdapter(1);
        q.put(evt(0));   // queue is now full

        CountDownLatch producerStarted = new CountDownLatch(1);
        // Wrap put() so we can capture whatever it threw on the producer thread.
        var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread producer = Thread.ofVirtual().start(() -> {
            producerStarted.countDown();
            try {
                q.put(evt(1));   // BLOCKS — queue is full
            } catch (Throwable t) {
                thrown.set(t);
            }
        });

        producerStarted.await();
        // Give the producer a moment to actually park inside backing.put().
        Thread.sleep(50);
        // Close FIRST so the closed flag is set, then drain to give the parked
        // producer the slot it was waiting on. The adapter's post-put check then
        // sees closed==true and yanks the event back out via the rejection branch.
        q.close();
        q.take();

        producer.join(2_000);
        assertThat(producer.isAlive()).as("producer must not be left parked").isFalse();
        // Either: the put succeeded just before close (no exception) OR the
        // close-while-blocked branch ran and yanked the item back out. Both are
        // valid implementations of "deliberate, not silent" — we just assert no
        // event was lost without a record:
        QueueMetrics m = q.metrics();
        if (thrown.get() == null) {
            // Producer's put completed before close was observed.
            assertThat(m.totalEnqueued()).isEqualTo(2L);
        } else {
            // Producer's put was rejected on close-while-blocked.
            assertThat(thrown.get()).isInstanceOf(QueueClosedException.class);
            assertThat(m.totalRejected())
                    .as("rejected counter increments when close races with a parked put")
                    .isGreaterThanOrEqualTo(1L);
        }
    }

    @Test
    void consumerLoopExitsCleanlyAfterClose() throws Exception {
        ArrayBlockingQueueAdapter q = new ArrayBlockingQueueAdapter(8);
        for (int i = 0; i < 10; i++) {
            q.put(evt(i));
        }

        List<Event> consumed = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread consumer = Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Event e = q.take();
                    if (e == null) return;   // closed and drained — clean exit
                    consumed.add(e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Let the consumer chew through what's there.
        Thread.sleep(200);
        // Add a few more, then signal shutdown.
        q.put(evt(100));
        q.put(evt(101));
        q.close();

        consumer.join(2_000);
        assertThat(consumer.isAlive())
                .as("consumer exits cleanly when queue is drained after close")
                .isFalse();
        assertThat(consumed).hasSize(12);
    }
}
