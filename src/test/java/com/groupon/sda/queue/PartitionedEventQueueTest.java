package com.groupon.sda.queue;

import com.groupon.sda.domain.event.DependencyObservedEvent;
import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.domain.event.DependencyRemovedEvent;
import com.groupon.sda.domain.event.Event;
import com.groupon.sda.domain.event.HeartbeatEvent;
import com.groupon.sda.domain.event.ServiceMetadataEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionedEventQueueTest {

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");

    @Test
    void allEventsForSameEdgeRouteToSamePartition() {
        PartitionedEventQueue q = new PartitionedEventQueue(8, 64);
        Event observed = new DependencyObservedEvent("e1", T0, "checkout", "payments", 10, Status.ok);
        Event removed = new DependencyRemovedEvent("e2", T0, "checkout", "payments");

        int p1 = q.partitionFor(observed);
        int p2 = q.partitionFor(removed);

        // Both edge events for (checkout, payments) MUST hash to the same consumer.
        assertThat(p1).isEqualTo(p2);
    }

    @Test
    void differentEdgesDistributeAcrossPartitions() {
        PartitionedEventQueue q = new PartitionedEventQueue(8, 64);
        // Build a few different edges; they should land in more than one partition
        // (sanity check that the hash isn't pinning everything to one queue).
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < 32; i++) {
            Event e = new DependencyObservedEvent(
                    "e-" + i, T0, "src-" + i, "tgt-" + (i % 5), 10, Status.ok);
            seen.add(q.partitionFor(e));
        }
        assertThat(seen).hasSizeGreaterThan(1);
    }

    @Test
    void serviceEventsRouteByServiceId() {
        PartitionedEventQueue q = new PartitionedEventQueue(8, 64);
        Event meta = new ServiceMetadataEvent("e1", T0, "svc-A", Map.of("team", "a"));
        Event hb = new HeartbeatEvent("e2", T0, "svc-A");
        // Same service id → same partition for both event kinds.
        assertThat(q.partitionFor(meta)).isEqualTo(q.partitionFor(hb));
    }

    @Test
    void publishAndPartitionTakeRoundTrip() throws Exception {
        PartitionedEventQueue q = new PartitionedEventQueue(4, 64);
        Event e = new DependencyObservedEvent("e1", T0, "a", "b", 10, Status.ok);
        q.publish(e);

        int p = q.partitionFor(e);
        Event taken = q.partition(p).take();
        assertThat(taken).isSameAs(e);

        // Other partitions are empty.
        for (int i = 0; i < q.partitionCount(); i++) {
            if (i == p) continue;
            assertThat(q.partition(i).size()).isZero();
        }
    }

    @Test
    void closeClosesEveryPartition() {
        PartitionedEventQueue q = new PartitionedEventQueue(3, 8);
        assertThat(q.isClosed()).isFalse();
        q.close();
        assertThat(q.isClosed()).isTrue();
        for (int i = 0; i < q.partitionCount(); i++) {
            assertThat(q.partition(i).isClosed()).isTrue();
        }
    }
}
