package com.groupon.sda.queue;

import com.groupon.sda.domain.event.DependencyObservedEvent;
import com.groupon.sda.domain.event.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArrayBlockingQueueAdapterTest {

    private static Event event(String id) {
        return new DependencyObservedEvent(
                id, Instant.parse("2026-05-10T00:00:00Z"),
                "a", "b", 10, DependencyObservedEvent.Status.ok);
    }

    @Test
    void putAndTakeReturnsSameEvent() throws Exception {
        EventQueue q = new ArrayBlockingQueueAdapter(4);
        Event in = event("e1");
        q.put(in);
        assertThat(q.size()).isEqualTo(1);

        Event out = q.poll(1, TimeUnit.SECONDS);
        assertThat(out).isSameAs(in);
        assertThat(q.size()).isZero();
    }

    @Test
    void offerReturnsFalseAndCountsDropOnTimeout() throws Exception {
        EventQueue q = new ArrayBlockingQueueAdapter(1);
        q.put(event("filler"));

        boolean accepted = q.offer(event("late"), 50, TimeUnit.MILLISECONDS);

        assertThat(accepted).isFalse();
        assertThat(q.metrics().totalDropped()).isEqualTo(1);
        assertThat(q.metrics().totalEnqueued()).isEqualTo(1);
    }

    @Test
    void putBlocksUntilConsumerMakesRoom() throws Exception {
        EventQueue q = new ArrayBlockingQueueAdapter(1);
        q.put(event("filler"));

        ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger producerFinished = new AtomicInteger(0);
        var producerFuture = io.submit(() -> {
            q.put(event("blocked"));
            producerFinished.incrementAndGet();
            return null;
        });

        // Producer should still be parked.
        Thread.sleep(100);
        assertThat(producerFinished.get()).isZero();

        // Free up a slot — producer must unblock.
        Event drained = q.poll(1, TimeUnit.SECONDS);
        assertThat(drained).isNotNull();

        producerFuture.get(1, TimeUnit.SECONDS);
        assertThat(producerFinished.get()).isOne();

        // Block-time histogram should have observed the wait.
        assertThat(q.metrics().putBlockedNanos()).isGreaterThan(TimeUnit.MILLISECONDS.toNanos(50));
        io.shutdown();
    }

    @Test
    void closeRejectsFurtherPuts() {
        EventQueue q = new ArrayBlockingQueueAdapter(4);
        q.close();

        assertThatThrownBy(() -> q.put(event("after-close")))
                .isInstanceOf(QueueClosedException.class);
        assertThat(q.metrics().totalRejected()).isEqualTo(1);
    }

    @Test
    void takeReturnsNullWhenClosedAndDrained() throws Exception {
        EventQueue q = new ArrayBlockingQueueAdapter(4);
        q.put(event("a"));
        q.put(event("b"));
        q.close();

        // Drain the two queued events first.
        assertThat(q.take()).isNotNull();
        assertThat(q.take()).isNotNull();
        // Then take returns null to signal closed-and-drained.
        assertThat(q.take()).isNull();
    }

    @Test
    void multiProducerMultiConsumerSeesEveryEventExactlyOnce() throws Exception {
        int producers = 4;
        int consumers = 4;
        int eventsPerProducer = 1_000;
        int total = producers * eventsPerProducer;

        EventQueue q = new ArrayBlockingQueueAdapter(64);
        ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
        CountDownLatch consumerStart = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producers);

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        for (int c = 0; c < consumers; c++) {
            pool.submit(() -> {
                consumerStart.await();
                while (true) {
                    Event e = q.take();
                    if (e == null) return null;        // closed + drained
                    seen.add(e.eventId());
                }
            });
        }

        for (int p = 0; p < producers; p++) {
            final int producerId = p;
            pool.submit(() -> {
                for (int i = 0; i < eventsPerProducer; i++) {
                    q.put(event("p" + producerId + "-i" + i));
                }
                producersDone.countDown();
                return null;
            });
        }

        consumerStart.countDown();
        assertThat(producersDone.await(10, TimeUnit.SECONDS)).isTrue();
        q.close();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(seen).hasSize(total);
        assertThat(seen.stream().distinct().count()).isEqualTo(total);   // no duplicates delivered
        assertThat(q.metrics().totalEnqueued()).isEqualTo(total);
        assertThat(q.metrics().totalDequeued()).isEqualTo(total);
        assertThat(q.metrics().currentDepth()).isZero();
    }

    @Test
    void rejectsZeroOrNegativeCapacity() {
        assertThatThrownBy(() -> new ArrayBlockingQueueAdapter(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArrayBlockingQueueAdapter(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeIsIdempotent() {
        EventQueue q = new ArrayBlockingQueueAdapter(4);
        q.close();
        q.close();
        assertThat(q.isClosed()).isTrue();
    }

    @Test
    void closedQueueDrainsRemainingItemsBeforeReturningNull() throws Exception {
        EventQueue q = new ArrayBlockingQueueAdapter(4);
        List<String> ids = List.of("a", "b", "c");
        for (String id : ids) q.put(event(id));
        q.close();

        for (String expected : ids) {
            Event got = q.take();
            assertThat(got).isNotNull();
            assertThat(got.eventId()).isEqualTo(expected);
        }
        assertThat(q.take()).isNull();
    }
}
