package com.groupon.sda.persistence;

import com.groupon.sda.config.HealthProperties;
import com.groupon.sda.graph.ServiceGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Rehydrates the in-memory {@link ServiceGraph} from SQLite after boot. Runs after
 * {@link SqlitePragmaInitializer} (so WAL is on for the bulk read) and before the
 * ingest consumers start (so the first event applied finds the graph already
 * consistent with disk).
 *
 * <p>Three reads, in order:
 * <ol>
 *   <li>{@code services} — every node and its metadata + last heartbeat.</li>
 *   <li>{@code edges} — every live edge with rolling stats restored.</li>
 *   <li>{@code edge_samples} — recent samples (within {@code sda.health.window-seconds})
 *       replayed into per-edge deques.</li>
 * </ol>
 *
 * <p>If the DB is empty (first boot), all three reads return empty lists and the
 * graph stays empty. No event-log replay — the DB is the persisted state.
 */
@Component
@Order(10)
public class GraphRestoreRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphRestoreRunner.class);

    private final ServiceRepository services;
    private final EdgeRepository edges;
    private final EdgeSampleRepository edgeSamples;
    private final ServiceGraph graph;
    private final Clock clock;
    private final Duration sampleAgeCap;

    @Autowired
    public GraphRestoreRunner(ServiceRepository services,
                              EdgeRepository edges,
                              EdgeSampleRepository edgeSamples,
                              ServiceGraph graph,
                              Clock clock,
                              HealthProperties healthProperties) {
        this.services = services;
        this.edges = edges;
        this.edgeSamples = edgeSamples;
        this.graph = graph;
        this.clock = clock;
        this.sampleAgeCap = Duration.ofSeconds(healthProperties.windowSeconds());
    }

    @EventListener(ContextRefreshedEvent.class)
    public void restore() {
        long t0 = System.nanoTime();

        var serviceRows = services.findAll();
        for (ServiceRepository.ServiceRow row : serviceRows) {
            // applyServiceMetadata and applyHeartbeat both ensure the node exists,
            // so an explicit upsertNodeForRestore isn't needed. Each apply takes the
            // graph's write lock internally.
            graph.applyServiceMetadata(row.id(), row.team(), row.tier(), row.region());
            if (row.lastHeartbeat() != null) {
                graph.applyHeartbeat(row.id(), row.lastHeartbeat());
            }
        }

        var edgeRows = edges.findAll();
        for (EdgeRepository.EdgeRow row : edgeRows) {
            graph.upsertEdgeForRestore(row.source(), row.target(),
                    row.rollingAvgLatencyMs(), row.sampleCount(),
                    row.lastObservedTs());
        }

        Instant cutoff = clock.instant().minus(sampleAgeCap);
        var sampleRows = edgeSamples.findRecent(cutoff.toEpochMilli());
        for (EdgeSampleRepository.SampleRow row : sampleRows) {
            graph.restoreEdgeSample(row.source(), row.target(), row.sample());
        }

        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("Graph restored from SQLite: {} services, {} edges, {} samples ({} ms)",
                serviceRows.size(), edgeRows.size(), sampleRows.size(), ms);
    }
}
