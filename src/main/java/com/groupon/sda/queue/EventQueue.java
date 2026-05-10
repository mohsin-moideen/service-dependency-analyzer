package com.groupon.sda.queue;

import com.groupon.sda.domain.event.Event;

import java.util.concurrent.TimeUnit;

/**
 * Bounded multi-producer / multi-consumer queue for events.
 *
 * <p>Per the spec, this is built from in-process primitives only — no Kafka, RabbitMQ, NATS,
 * Redis Streams, SQS, or any other off-the-shelf broker. The default implementation
 * ({@link ArrayBlockingQueueAdapter}) wraps {@link java.util.concurrent.ArrayBlockingQueue},
 * which itself is a {@code ReentrantLock} + two {@code Condition}s + a circular array.
 *
 * <h2>Backpressure</h2>
 * Default policy is <b>block</b>: {@link #put(Event)} parks the producer thread until a slot
 * frees up. Producers self-throttle to consumer throughput; events are never silently dropped.
 * Callers that prefer to shed deliberately can use {@link #offer(Event, long, TimeUnit)} and
 * count the {@code false} returns.
 *
 * <h2>Close / drain</h2>
 * {@link #close()} signals "no more puts" and wakes anyone parked on either condition.
 * After close, {@link #put} / {@link #offer} reject new items; {@link #take} / {@link #poll}
 * keep returning items until the buffer empties, then return {@code null} so consumers can
 * exit cleanly.
 *
 * <h2>What this interface does NOT do</h2>
 * No deduplication. No persistence. No knowledge of event semantics beyond the type parameter.
 * Idempotency lives at the consumer (see {@code SeenEventsCache} + {@code processed_events}).
 */
public interface EventQueue {

    /**
     * Insert {@code event}, blocking until space is available or the queue is closed.
     *
     * @throws InterruptedException  if the calling thread is interrupted while waiting
     * @throws QueueClosedException  if the queue has been closed
     */
    void put(Event event) throws InterruptedException;

    /**
     * Insert {@code event}, waiting up to the given timeout for space.
     *
     * @return {@code true} if the event was enqueued; {@code false} if the timeout elapsed
     *         (caller may treat this as a deliberate shed)
     * @throws InterruptedException  if interrupted while waiting
     * @throws QueueClosedException  if the queue has been closed
     */
    boolean offer(Event event, long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Remove and return the next event, blocking until one is available.
     *
     * @return the next event, or {@code null} if the queue is closed and drained
     * @throws InterruptedException  if interrupted while waiting
     */
    Event take() throws InterruptedException;

    /**
     * Remove and return the next event, waiting up to the given timeout.
     *
     * @return the next event, or {@code null} if no event arrived within the timeout
     *         <i>or</i> the queue is closed and drained
     */
    Event poll(long timeout, TimeUnit unit) throws InterruptedException;

    /** Number of events currently buffered. */
    int size();

    /** Free slots available for {@link #put} / {@link #offer}. */
    int remainingCapacity();

    /**
     * Signal that no more events will be put. Idempotent. Wakes all parked producers
     * (so they can throw {@link QueueClosedException}) and consumers (so they can drain
     * remaining items and exit on {@code null}).
     */
    void close();

    boolean isClosed();

    /** Snapshot of operational counters. Safe to call concurrently with put/take. */
    QueueMetrics metrics();
}
