package com.groupon.sda.queue;

import com.groupon.sda.domain.event.DependencyObservedEvent;
import com.groupon.sda.domain.event.DependencyRemovedEvent;
import com.groupon.sda.domain.event.Event;
import com.groupon.sda.domain.event.HeartbeatEvent;
import com.groupon.sda.domain.event.ServiceMetadataEvent;

import java.util.concurrent.TimeUnit;

/**
 * Multi-partition wrapper around a fixed set of {@link EventQueue}s. Producers and the
 * HTTP ingest endpoint publish through {@link #publish}/{@link #tryPublish}; each event
 * is routed to one consumer's queue by hashing a partition key. Consumers each own one
 * partition (see {@code ConsumerManager}), which preserves <b>per-edge ordering</b>:
 * every event for a given {@code (source, target)} pair is processed by the same
 * consumer thread in arrival order.
 *
 * <p>Why per-edge ordering matters: with a single shared queue and N consumers, two
 * consumers can pull adjacent same-edge events and apply them in the wrong order.
 * That doesn't break the rolling-average mean (it's order-independent) but it does
 * mean that an {@code observed → removed → observed} sequence — or any other flow
 * where the relative order of same-edge events affects the final state — becomes
 * non-deterministic. Partitioning eliminates that window.
 *
 * <h2>Partition keys</h2>
 * <ul>
 *   <li>{@code dependency_observed} / {@code dependency_removed}: hash the
 *       {@code source + "→" + target} string. Both events for an edge land in the
 *       same partition.</li>
 *   <li>{@code service_metadata} / {@code heartbeat}: hash the service id.</li>
 * </ul>
 *
 * <h2>Backpressure</h2>
 * Each partition has its own bounded {@link EventQueue}. A hot edge filling its
 * partition's queue blocks producers writing to <i>that</i> partition while leaving
 * the others free — graceful degradation under skew. The HTTP ingest path uses
 * {@link #tryPublish} so a full partition returns 503 instead of pinning a Tomcat
 * thread.
 *
 * <h2>Lifecycle</h2>
 * {@link #close()} closes every partition. Consumers each see {@code take() == null}
 * once their queue is closed and drained, and exit. {@link #isClosed()} is true only
 * when every partition is closed.
 */
public class PartitionedEventQueue {

    private final EventQueue[] partitions;

    public PartitionedEventQueue(int partitionCount, int queueCapacity) {
        if (partitionCount <= 0) {
            throw new IllegalArgumentException("partitionCount must be > 0, got " + partitionCount);
        }
        this.partitions = new EventQueue[partitionCount];
        for (int i = 0; i < partitionCount; i++) {
            partitions[i] = new ArrayBlockingQueueAdapter(queueCapacity);
        }
    }

    public int partitionCount() {
        return partitions.length;
    }

    /** Direct access to one partition — used by {@code ConsumerManager} so each consumer owns a queue. */
    public EventQueue partition(int index) {
        return partitions[index];
    }

    /** The partition this event will route to. Stable for any given event. */
    public int partitionFor(Event event) {
        String key = partitionKey(event);
        // Math.floorMod handles negative hashCodes correctly.
        return Math.floorMod(key.hashCode(), partitions.length);
    }

    /** Block until the event is enqueued or the queue is closed. */
    public void publish(Event event) throws InterruptedException {
        partitions[partitionFor(event)].put(event);
    }

    /** Bounded-wait publish; returns false on timeout (deliberate shed). */
    public boolean tryPublish(Event event, long timeout, TimeUnit unit) throws InterruptedException {
        return partitions[partitionFor(event)].offer(event, timeout, unit);
    }

    /** Close every partition. Idempotent. Producers parked in {@link #publish} wake with {@link QueueClosedException}. */
    public void close() {
        for (EventQueue q : partitions) {
            q.close();
        }
    }

    public boolean isClosed() {
        for (EventQueue q : partitions) {
            if (!q.isClosed()) return false;
        }
        return true;
    }

    /** Total events buffered across all partitions. */
    public int size() {
        int total = 0;
        for (EventQueue q : partitions) total += q.size();
        return total;
    }

    private static String partitionKey(Event event) {
        return switch (event) {
            case DependencyObservedEvent o -> o.source() + "" + o.target();
            case DependencyRemovedEvent r -> r.source() + "" + r.target();
            case ServiceMetadataEvent m -> m.service();
            case HeartbeatEvent h -> h.service();
        };
    }
}
