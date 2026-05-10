package com.groupon.sda.events.generator;

import com.groupon.sda.domain.event.DependencyObservedEvent;
import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.domain.event.DependencyRemovedEvent;
import com.groupon.sda.domain.event.Event;
import com.groupon.sda.domain.event.HeartbeatEvent;
import com.groupon.sda.domain.event.ServiceMetadataEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Pre-generates a finite batch of events at construction with the shape the spec calls
 * out:
 * <ul>
 *   <li>~serviceCount distinct services</li>
 *   <li>A handful of fan-in hubs (databases, auth, cache, logger) referenced by ~half
 *       of all dependency observations</li>
 *   <li>{@code cyclesToInject} explicit cycles</li>
 *   <li>Long-tailed latency distribution (most fast, a few slow)</li>
 *   <li>Configurable error rate (mostly OK, some error/timeout)</li>
 *   <li>Mix of all four event types (observed, removed, metadata, heartbeat)</li>
 *   <li>{@code duplicateRate} duplicate event ids to exercise the dedup path</li>
 * </ul>
 *
 * <p>Events are stored in a {@link ConcurrentLinkedQueue} so multi-threaded producers
 * can drain it concurrently. {@link #next()} returns {@code null} once exhausted.
 *
 * <p>The generated topology is seeded by {@code seed} so a given config produces a
 * reproducible event stream across runs.
 */
public class SyntheticEventGenerator implements EventGenerator {

    private static final Logger log = LoggerFactory.getLogger(SyntheticEventGenerator.class);

    private final Queue<Event> events;
    private final int totalEvents;

    public SyntheticEventGenerator(EventGeneratorProperties props, Clock clock) {
        Random rnd = new Random(props.seed());
        Instant t0 = clock.instant();

        // 1. Service ids — a mix of named services and svc-NNNN.
        List<String> services = buildServiceIds(props.serviceCount(), rnd);
        // 2. Hubs — pick the first hubCount; downstream observations bias toward them.
        List<String> hubs = services.subList(0, Math.min(props.hubCount(), services.size()));
        // 3. Topology — for each service, a few outgoing edges (biased to hubs).
        List<String[]> edges = buildEdges(services, hubs, rnd);
        // 4. Inject explicit cycles by closing back-edges.
        injectCycles(edges, services, rnd, props.cyclesToInject());

        // 5. Stream events that drive these edges + sprinkle metadata, heartbeats,
        //    occasional removals, and (per duplicateRate) exact-replay duplicates of a
        //    recent event (the most realistic dup pattern — an upstream retry replays a
        //    recent id, not one from minutes ago).
        ArrayList<Event> stream = new ArrayList<>(props.eventCount());
        Deque<Event> recent = new ArrayDeque<>(64);
        for (int i = 0; i < props.eventCount(); i++) {
            if (!recent.isEmpty() && rnd.nextDouble() < props.duplicateRate()) {
                List<Event> snapshot = new ArrayList<>(recent);
                Event dup = snapshot.get(rnd.nextInt(snapshot.size()));
                stream.add(dup);
                continue;
            }
            Event e = nextEvent(rnd, t0, i, services, edges, props.errorRate());
            stream.add(e);
            recent.addLast(e);
            if (recent.size() > 64) recent.removeFirst();
        }
        Collections.shuffle(stream, rnd);   // interleave types

        this.events = new ConcurrentLinkedQueue<>(stream);
        this.totalEvents = stream.size();
        log.info("synthetic generator built: {} services, {} edges, {} events ({} hubs, {} cycles)",
                services.size(), edges.size(), totalEvents, hubs.size(), props.cyclesToInject());
    }

    @Override
    public Event next() {
        return events.poll();
    }

    @Override
    public int totalEvents() {
        return totalEvents;
    }

    // ---- topology builders ---------------------------------------------------------

    private static final String[] NAMED = {
            "checkout-api", "payments-service", "auth-service", "cache-redis",
            "db-primary", "db-replica", "logger", "search-index", "user-profile",
            "inventory-service", "shipping-api", "notifications", "billing"
    };

    private static List<String> buildServiceIds(int n, Random rnd) {
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < Math.min(NAMED.length, n); i++) ids.add(NAMED[i]);
        for (int i = ids.size(); i < n; i++) ids.add(String.format("svc-%05d", i));
        Collections.shuffle(ids, rnd);
        return ids;
    }

    private static List<String[]> buildEdges(List<String> services, List<String> hubs, Random rnd) {
        List<String[]> edges = new ArrayList<>(services.size() * 3);
        for (String src : services) {
            int fanout = 1 + rnd.nextInt(3);   // 1–3 outgoing edges
            for (int k = 0; k < fanout; k++) {
                String tgt;
                if (!hubs.isEmpty() && rnd.nextDouble() < 0.5) {
                    tgt = hubs.get(rnd.nextInt(hubs.size()));
                } else {
                    tgt = services.get(rnd.nextInt(services.size()));
                }
                if (!tgt.equals(src)) {
                    edges.add(new String[]{src, tgt});
                }
            }
        }
        return edges;
    }

    private static void injectCycles(List<String[]> edges, List<String> services,
                                     Random rnd, int count) {
        for (int i = 0; i < count; i++) {
            int len = 2 + rnd.nextInt(3);   // 2–4 nodes per cycle
            List<String> nodes = new ArrayList<>(len);
            for (int j = 0; j < len; j++) nodes.add(services.get(rnd.nextInt(services.size())));
            for (int j = 0; j < len; j++) {
                edges.add(new String[]{nodes.get(j), nodes.get((j + 1) % len)});
            }
        }
    }

    // ---- event factory -------------------------------------------------------------

    private static Event nextEvent(Random rnd, Instant t0, int index,
                                   List<String> services, List<String[]> edges,
                                   double errorRate) {
        // Deterministic-per-seed id. Looks UUID-ish but is reproducible across runs.
        String eventId = String.format("e-%08x-%08x", rnd.nextInt(), index);
        // Slight backwards-skew so some events arrive "out of order" wrt arrival.
        Instant ts = t0.plusMillis((long) index + rnd.nextInt(200) - 100);

        double roll = rnd.nextDouble();
        if (roll < 0.78) {
            // dependency_observed (most events)
            String[] e = edges.get(rnd.nextInt(edges.size()));
            int latency = sampleLatencyMs(rnd);
            Status status = sampleStatus(rnd, errorRate);
            return new DependencyObservedEvent(eventId, ts, e[0], e[1], latency, status);
        } else if (roll < 0.88) {
            // heartbeat
            String svc = services.get(rnd.nextInt(services.size()));
            return new HeartbeatEvent(eventId, ts, svc);
        } else if (roll < 0.94) {
            // service_metadata (partial — at least one field)
            String svc = services.get(rnd.nextInt(services.size()));
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("team", "team-" + rnd.nextInt(8));
            attrs.put("tier", rnd.nextBoolean() ? "tier-0" : "tier-1");
            attrs.put("region", "region-" + rnd.nextInt(4));
            return new ServiceMetadataEvent(eventId, ts, svc, attrs);
        } else {
            // dependency_removed (last bucket — small fraction)
            String[] e = edges.get(rnd.nextInt(edges.size()));
            return new DependencyRemovedEvent(eventId, ts, e[0], e[1]);
        }
    }

    /** Long-tailed latency: most under 50ms, a thin tail to ~500ms. */
    private static int sampleLatencyMs(Random rnd) {
        double u = rnd.nextDouble();
        if (u < 0.95) return 1 + rnd.nextInt(50);
        return 50 + rnd.nextInt(450);
    }

    private static Status sampleStatus(Random rnd, double errorRate) {
        if (rnd.nextDouble() >= errorRate) return Status.ok;
        return rnd.nextBoolean() ? Status.error : Status.timeout;
    }
}
