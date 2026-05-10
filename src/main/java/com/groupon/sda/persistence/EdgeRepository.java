package com.groupon.sda.persistence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads and writes the {@code edges} table — current set of live edges and their
 * rolling latency stats. The {@code edge_samples} table (handled separately by
 * {@link EdgeSampleRepository}) is the per-sample audit trail used to rebuild the
 * in-memory health window on boot.
 *
 * <p>Every write replaces the rolling stats with the consumer's latest computed values.
 * The consumer computes {@code rolling_avg_latency_ms} in memory (incremental running
 * average inside {@code Edge.recordObservation}) and writes the post-update values here;
 * we don't recompute server-side.
 */
@Repository
public class EdgeRepository {

    private final JdbcTemplate jdbc;

    @Autowired
    public EdgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert-or-update with monotonic-sample-count semantics.
     *
     * <p>Two consumers observing the same edge concurrently mutate in-memory under the
     * graph's write lock (so the second one sees the first's update and produces a
     * larger {@code sampleCount}). Their persistence transactions, however, can commit
     * in any order. The {@code ON CONFLICT} clause keeps the row consistent with the
     * <i>highest</i> sample count seen, which is the latest in-memory state regardless
     * of commit order.
     */
    public void upsert(String source, String target, double rollingAvgLatencyMs, long sampleCount) {
        jdbc.update("""
                INSERT INTO edges(source, target, rolling_avg_latency_ms, sample_count)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(source, target) DO UPDATE SET
                    rolling_avg_latency_ms = CASE
                        WHEN excluded.sample_count >= edges.sample_count
                            THEN excluded.rolling_avg_latency_ms
                        ELSE edges.rolling_avg_latency_ms
                    END,
                    sample_count = MAX(edges.sample_count, excluded.sample_count)
                """, source, target, rollingAvgLatencyMs, sampleCount);
    }

    /** @return number of rows deleted (0 if the edge wasn't in the table) */
    public int delete(String source, String target) {
        return jdbc.update("DELETE FROM edges WHERE source = ? AND target = ?", source, target);
    }

    public List<EdgeRow> findAll() {
        return jdbc.query("SELECT source, target, rolling_avg_latency_ms, sample_count FROM edges",
                ROW_MAPPER);
    }

    public record EdgeRow(String source, String target, double rollingAvgLatencyMs, long sampleCount) {
    }

    private static final RowMapper<EdgeRow> ROW_MAPPER = (rs, rowNum) -> new EdgeRow(
            rs.getString("source"),
            rs.getString("target"),
            rs.getDouble("rolling_avg_latency_ms"),
            rs.getLong("sample_count"));
}
