package com.groupon.sda.persistence;

import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.domain.graph.Sample;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Per-sample audit trail for the {@code health()} window. On boot, the most recent
 * {@code retentionWindow} of rows is read into the in-memory {@code Edge.recentSamples}
 * deques. Older rows are pruned periodically (see {@code PersistenceMaintenance}).
 */
@Repository
public class EdgeSampleRepository {

    private final JdbcTemplate jdbc;

    @Autowired
    public EdgeSampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String source, String target, Instant ts, int latencyMs, Status status) {
        jdbc.update("""
                INSERT INTO edge_samples(source, target, ts, latency_ms, status)
                VALUES (?, ?, ?, ?, ?)
                """, source, target, ts.toEpochMilli(), latencyMs, status.name());
    }

    /**
     * Read every sample with {@code ts >= sinceMs}, sorted oldest-first for replay.
     *
     * <p>Boundary semantics intentionally match {@code Edge.evictByAge}, which uses
     * {@code isBefore(cutoff)} (strict) — i.e., a sample with {@code ts == cutoff} is
     * <i>kept</i> in memory. Using {@code >=} here means the same sample also makes it
     * back into memory across restart, so disk and heap agree on the boundary.
     */
    public List<SampleRow> findRecent(long sinceMs) {
        return jdbc.query("""
                SELECT source, target, ts, latency_ms, status
                FROM edge_samples
                WHERE ts >= ?
                ORDER BY ts ASC
                """, ROW_MAPPER, sinceMs);
    }

    /** @return number of rows deleted */
    public int pruneOlderThan(long olderThanMs) {
        return jdbc.update("DELETE FROM edge_samples WHERE ts < ?", olderThanMs);
    }

    public record SampleRow(String source, String target, Sample sample) {
    }

    private static final RowMapper<SampleRow> ROW_MAPPER = (rs, rowNum) -> {
        Sample s = new Sample(
                Instant.ofEpochMilli(rs.getLong("ts")),
                rs.getInt("latency_ms"),
                Status.valueOf(rs.getString("status")));
        return new SampleRow(rs.getString("source"), rs.getString("target"), s);
    };
}
