package com.groupon.sda.queue;

import com.groupon.sda.domain.event.Event;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * {@link EventQueue} backed by a {@link ArrayBlockingQueue}. Adds:
 * <ul>
 *   <li>A {@code closed} flag with deterministic close/drain semantics — the JDK queue
 *       has no native "closed" state.</li>
 *   <li>{@link QueueMetrics} counters (depth, enqueued, dequeued, dropped, rejected,
 *       putBlockedNanos).</li>
 * </ul>
 *
 * <h3>Why ArrayBlockingQueue?</h3>
 * It's a {@code ReentrantLock} + two {@code Condition}s + a circular {@code Object[]} —
 * literally the "mutex-protected ring buffer" the spec invites. Hand-rolling the same code
 * gains nothing at runtime, so we wrap and add the close + metrics layers on top.
 *
 * <h3>Close behaviour</h3>
 * After {@link #close()}:
 * <ul>
 *   <li>{@link #put} / {@link #offer} throw {@link QueueClosedException} immediately.</li>
 *   <li>{@link #take} / {@link #poll} keep returning items until the buffer empties; once
 *       empty they return {@code null} so consumers can exit their loop cleanly.</li>
 * </ul>
 * Consumers don't park indefinitely inside the JDK queue: our {@link #take} / {@link #poll}
 * loop on a 50ms timed {@code poll} so the {@code closed} flag is observed within at most
 * 50ms of {@link #close()}. No thread-interruption gymnastics are required.
 */
public class ArrayBlockingQueueAdapter implements EventQueue {

    private final ArrayBlockingQueue<Event> backing;
    private final int capacity;
    private volatile boolean closed = false;

    private final LongAdder enqueued = new LongAdder();
    private final LongAdder dequeued = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder putBlockedNanos = new LongAdder();

    public ArrayBlockingQueueAdapter(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        this.capacity = capacity;
        this.backing = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public void put(Event event) throws InterruptedException {
        if (closed) {
            rejected.increment();
            throw new QueueClosedException();
        }
        long start = System.nanoTime();
        try {
            backing.put(event);
        } finally {
            putBlockedNanos.add(System.nanoTime() - start);
        }
        // Edge case: producer was parked in put(), close() ran, slot freed, put completed.
        // Stay consistent with the contract by yanking the item back out and rejecting.
        if (closed) {
            if (backing.remove(event)) {
                rejected.increment();
                throw new QueueClosedException("queue closed while put was blocked");
            }
        }
        enqueued.increment();
    }

    @Override
    public boolean offer(Event event, long timeout, TimeUnit unit) throws InterruptedException {
        if (closed) {
            rejected.increment();
            throw new QueueClosedException();
        }
        long start = System.nanoTime();
        boolean accepted;
        try {
            accepted = backing.offer(event, timeout, unit);
        } finally {
            putBlockedNanos.add(System.nanoTime() - start);
        }
        if (!accepted) {
            dropped.increment();
            return false;
        }
        if (closed && backing.remove(event)) {
            rejected.increment();
            throw new QueueClosedException("queue closed while offer was blocked");
        }
        enqueued.increment();
        return true;
    }

    @Override
    public Event take() throws InterruptedException {
        // If closed and drained, return null instead of blocking forever.
        if (closed && backing.isEmpty()) {
            return null;
        }
        // Use a timed poll loop so close() (which we don't pair with a Condition) can
        // be observed without indefinite waiting. 50ms is a fine compromise between
        // shutdown latency and idle CPU.
        while (true) {
            Event e = backing.poll(50, TimeUnit.MILLISECONDS);
            if (e != null) {
                dequeued.increment();
                return e;
            }
            if (closed && backing.isEmpty()) {
                return null;
            }
        }
    }

    @Override
    public Event poll(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }
            long step = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50));
            Event e = backing.poll(step, TimeUnit.NANOSECONDS);
            if (e != null) {
                dequeued.increment();
                return e;
            }
            if (closed && backing.isEmpty()) {
                return null;
            }
        }
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public int remainingCapacity() {
        return backing.remainingCapacity();
    }

    @Override
    public void close() {
        closed = true;
        // Producers parked in put()/offer() will be released as consumers drain.
        // Consumers parked in our take() poll loop will see closed && empty within
        // at most 50ms. No need to interrupt threads — the polling cadence is the
        // wakeup mechanism.
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public QueueMetrics metrics() {
        return new QueueMetrics(
                backing.size(),
                capacity,
                enqueued.sum(),
                dequeued.sum(),
                dropped.sum(),
                rejected.sum(),
                putBlockedNanos.sum()
        );
    }
}
