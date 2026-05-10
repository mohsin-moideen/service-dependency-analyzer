package com.groupon.sda.persistence;

import com.groupon.sda.config.HealthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Background DB hygiene. Currently: prunes {@code edge_samples} rows older than the
 * configured retention window once a minute. Without this, the table grows unbounded
 * across long runs even though the in-memory deques are bounded.
 *
 * <p>The prune is intentionally cheap — a single {@code DELETE WHERE ts < ?} that uses
 * the {@code idx_edge_samples_ts} index. SQLite's WAL mode keeps it from blocking
 * concurrent reads/writes.
 */
@Component
public class PersistenceMaintenance {

    private static final Logger log = LoggerFactory.getLogger(PersistenceMaintenance.class);

    private final EdgeSampleRepository edgeSamples;
    private final Clock clock;
    private final Duration retention;

    @Autowired
    public PersistenceMaintenance(EdgeSampleRepository edgeSamples,
                                  Clock clock,
                                  HealthProperties health) {
        this.edgeSamples = edgeSamples;
        this.clock = clock;
        this.retention = Duration.ofSeconds(health.windowSeconds());
    }

    /**
     * Runs once a minute. The fixed delay (not fixed rate) means a slow prune doesn't
     * pile up overlapping runs.
     */
    @Scheduled(fixedDelayString = "PT1M")
    public void pruneExpiredSamples() {
        Instant cutoff = clock.instant().minus(retention);
        int deleted = edgeSamples.pruneOlderThan(cutoff.toEpochMilli());
        if (deleted > 0) {
            log.info("Pruned {} edge_samples rows older than {}", deleted, cutoff);
        }
    }
}
