package com.groupon.sda.queue;

/**
 * Snapshot of queue counters at a moment in time. Immutable; cheap to read.
 *
 * <p>Hooked up to Micrometer later via the actuator bridge — for now this is just a
 * value type that the queue's {@link EventQueue#metrics()} returns.
 *
 * @param currentDepth     events currently buffered ({@code 0..capacity})
 * @param capacity         configured capacity
 * @param totalEnqueued    cumulative successful {@code put}/{@code offer} calls
 * @param totalDequeued    cumulative successful {@code take}/{@code poll} calls
 * @param totalDropped     cumulative {@code offer} timeouts (deliberate sheds)
 * @param totalRejected    cumulative {@code put}/{@code offer} attempts after close
 * @param putBlockedNanos  cumulative time producers spent blocked inside {@code put}
 *                         (good leading indicator of consumer lag)
 */
public record QueueMetrics(
        int currentDepth,
        int capacity,
        long totalEnqueued,
        long totalDequeued,
        long totalDropped,
        long totalRejected,
        long putBlockedNanos
) {
}
