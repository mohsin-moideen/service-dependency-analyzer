package com.groupon.sda.ingest.consumer;

import com.groupon.sda.domain.event.DependencyObservedEvent;
import com.groupon.sda.domain.event.DependencyRemovedEvent;
import com.groupon.sda.domain.event.Event;
import com.groupon.sda.domain.event.HeartbeatEvent;
import com.groupon.sda.domain.event.ServiceMetadataEvent;
import com.groupon.sda.domain.graph.EdgeStats;
import com.groupon.sda.graph.ServiceGraph;
import com.groupon.sda.ingest.dedup.SeenEventsCache;
import com.groupon.sda.persistence.EdgeRepository;
import com.groupon.sda.persistence.EdgeSampleRepository;
import com.groupon.sda.persistence.ProcessedEventsRepository;
import com.groupon.sda.persistence.ServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-event apply logic. Three concerns, in order:
 *
 * <ol>
 *   <li><b>Atomic in-process claim</b> via {@link SeenEventsCache#tryClaim}. At most
 *       one consumer per id wins the claim; the loser confirms against the durable
 *       store and skips. Race-free across consumer threads.</li>
 *   <li><b>Restart confirmation</b> via {@link ProcessedEventsRepository#exists}.
 *       After a JVM restart the in-process cache is empty, but the DB still holds
 *       previously processed ids. Skip them.</li>
 *   <li><b>Apply</b> — in-memory mutation under the graph's write lock (returning
 *       the post-update rolling stats), then a single transaction that writes
 *       {@code processed_events} plus the data rows for this event.</li>
 * </ol>
 *
 * <p>Order rationale: in-memory before DB lets us persist authoritative post-mutation
 * rolling stats in a single round-trip. Concurrent observations on the same edge
 * commit in any order; {@link EdgeRepository#upsert}'s {@code ON CONFLICT
 * MAX(sample_count)} keeps the latest in-memory state.
 *
 * <p>Crash semantics: a JVM crash between in-memory mutation and DB commit leaves the
 * {@code processed_events} row absent. On the next boot, restore rebuilds in-memory
 * from DB (without the lost event); if the upstream re-delivers, the event is applied
 * fresh. The synthetic generator doesn't re-deliver — that event is gone, accepted
 * scope for the take-home.
 */
@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final SeenEventsCache cache;
    private final ProcessedEventsRepository processedEvents;
    private final ServiceRepository services;
    private final EdgeRepository edges;
    private final EdgeSampleRepository edgeSamples;
    private final ServiceGraph graph;
    private final Clock clock;
    private final TransactionTemplate tx;

    private final LongAdder applied = new LongAdder();
    private final LongAdder duplicatesViaCache = new LongAdder();
    private final LongAdder duplicatesViaDb = new LongAdder();

    public EventConsumer(SeenEventsCache cache,
                         ProcessedEventsRepository processedEvents,
                         ServiceRepository services,
                         EdgeRepository edges,
                         EdgeSampleRepository edgeSamples,
                         ServiceGraph graph,
                         Clock clock,
                         PlatformTransactionManager txManager) {
        this.cache = cache;
        this.processedEvents = processedEvents;
        this.services = services;
        this.edges = edges;
        this.edgeSamples = edgeSamples;
        this.graph = graph;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public void consume(Event event) {
        // Defend against malformed input (e.g., a JSON body deserialised to an Event with
        // a null id). Without this guard, cache.tryClaim(null) would NPE inside
        // ConcurrentHashMap.add and the runnable's catch-Throwable would log a noisy
        // "unhandled" rather than a clean drop.
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            log.warn("dropping event with missing id: {}", event);
            return;
        }
        if (!cache.tryClaim(event.eventId())) {
            // Another in-process consumer already claimed this id. With the exact
            // InMemorySetCache, tryClaim==false is authoritative — the winner will
            // commit (or already has). We skip without a DB round-trip.
            //
            // A future BloomFilterCache cannot use this code path as-is: a BF false
            // positive on contains() would let us drop a fresh event. When that
            // implementation lands, the consume() protocol needs to change (e.g.,
            // BF only as a fast-path that gates a slow-path DB confirm).
            duplicatesViaCache.increment();
            return;
        }

        // We won the claim. Restart confirmation against the durable store: an id
        // committed in a previous JVM run lives in DB but not in our (cold) cache.
        if (processedEvents.exists(event.eventId())) {
            duplicatesViaDb.increment();
            return;
        }

        applyAndPersist(event);
        applied.increment();
    }

    private void applyAndPersist(Event event) {
        switch (event) {
            case DependencyObservedEvent o -> {
                EdgeStats stats = graph.applyDependencyObserved(
                        o.source(), o.target(), o.timestamp(), o.latencyMs(), o.status());
                if (stats == null) {
                    // LWW rejected the event as stale (older than a tombstone or an
                    // existing edge's lastObservedTs). Mark it as processed so
                    // re-deliveries are deduped, but skip the data writes — there's
                    // nothing to update.
                    log.debug("ignoring stale observation {} for {}->{}",
                            o.eventId(), o.source(), o.target());
                    tx.executeWithoutResult(status ->
                            processedEvents.insertIfAbsent(o.eventId(), clock.instant()));
                    return;
                }
                tx.executeWithoutResult(status -> {
                    if (!processedEvents.insertIfAbsent(o.eventId(), clock.instant())) {
                        status.setRollbackOnly();
                        return;
                    }
                    services.ensure(o.source());
                    services.ensure(o.target());
                    edges.upsert(o.source(), o.target(),
                            stats.rollingAvgLatencyMs(), stats.sampleCount(),
                            o.timestamp());
                    edgeSamples.insert(o.source(), o.target(),
                            o.timestamp(), o.latencyMs(), o.status());
                });
            }
            case DependencyRemovedEvent r -> {
                graph.applyDependencyRemoved(r.source(), r.target(), r.timestamp());
                tx.executeWithoutResult(status -> {
                    if (!processedEvents.insertIfAbsent(r.eventId(), clock.instant())) {
                        status.setRollbackOnly();
                        return;
                    }
                    edges.delete(r.source(), r.target());
                });
            }
            case ServiceMetadataEvent m -> {
                Metadata md = extractMetadata(m);
                graph.applyServiceMetadata(m.service(), md.team(), md.tier(), md.region());
                tx.executeWithoutResult(status -> {
                    if (!processedEvents.insertIfAbsent(m.eventId(), clock.instant())) {
                        status.setRollbackOnly();
                        return;
                    }
                    services.upsertMetadata(m.service(), md.team(), md.tier(), md.region());
                });
            }
            case HeartbeatEvent h -> {
                graph.applyHeartbeat(h.service(), h.timestamp());
                tx.executeWithoutResult(status -> {
                    if (!processedEvents.insertIfAbsent(h.eventId(), clock.instant())) {
                        status.setRollbackOnly();
                        return;
                    }
                    services.upsertHeartbeat(h.service(), h.timestamp());
                });
            }
        }
    }

    private static Metadata extractMetadata(ServiceMetadataEvent m) {
        Map<String, String> attrs = m.attributes();
        if (attrs == null) return new Metadata(null, null, null);
        return new Metadata(attrs.get("team"), attrs.get("tier"), attrs.get("region"));
    }

    private record Metadata(String team, String tier, String region) {
    }

    public long appliedCount() {
        return applied.sum();
    }

    public long duplicatesCachedCount() {
        return duplicatesViaCache.sum();
    }

    public long duplicatesDbCount() {
        return duplicatesViaDb.sum();
    }
}
