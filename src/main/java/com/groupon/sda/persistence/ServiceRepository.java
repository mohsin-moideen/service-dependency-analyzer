package com.groupon.sda.persistence;

import com.groupon.sda.domain.graph.ServiceNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Reads and writes the {@code services} table — service id, optional metadata, and
 * the most recent heartbeat timestamp (millis-since-epoch, nullable).
 *
 * <p>Idempotent UPSERT semantics: every write is an {@code INSERT ... ON CONFLICT(id)
 * DO UPDATE} with {@code COALESCE} on each metadata field so partial events
 * (e.g., a heartbeat with no team/tier/region) don't blank previous values.
 */
@Repository
public class ServiceRepository {

    private final JdbcTemplate jdbc;

    @Autowired
    public ServiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertMetadata(String id, String team, String tier, String region) {
        jdbc.update("""
                INSERT INTO services(id, team, tier, region)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    team   = COALESCE(excluded.team,   services.team),
                    tier   = COALESCE(excluded.tier,   services.tier),
                    region = COALESCE(excluded.region, services.region)
                """, id, team, tier, region);
    }

    /**
     * Insert or update {@code last_heartbeat_ts}, keeping the newer of the existing
     * value and the incoming one.
     *
     * <p>The {@code COALESCE(services.last_heartbeat_ts, 0)} treats a missing previous
     * value as epoch zero. This works because all event timestamps in this system are
     * 2026-or-later (positive epoch-millis). If the project were ever ported to a
     * domain that legitimately uses pre-1970 timestamps, this default would silently
     * lose negative-epoch updates and would need to switch to
     * {@code COALESCE(..., -9223372036854775808)} or be rewritten as a CASE.
     */
    public void upsertHeartbeat(String id, Instant ts) {
        jdbc.update("""
                INSERT INTO services(id, last_heartbeat_ts)
                VALUES (?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    last_heartbeat_ts = MAX(
                        COALESCE(services.last_heartbeat_ts, 0),
                        excluded.last_heartbeat_ts
                    )
                """, id, ts.toEpochMilli());
    }

    /** Ensure a row exists for {@code id} without touching any field. */
    public void ensure(String id) {
        jdbc.update("INSERT OR IGNORE INTO services(id) VALUES (?)", id);
    }

    public List<ServiceRow> findAll() {
        return jdbc.query("SELECT id, team, tier, region, last_heartbeat_ts FROM services", ROW_MAPPER);
    }

    public record ServiceRow(String id, String team, String tier, String region, Instant lastHeartbeat) {
    }

    private static final RowMapper<ServiceRow> ROW_MAPPER = (rs, rowNum) -> {
        long hb = rs.getLong("last_heartbeat_ts");
        Instant heartbeat = rs.wasNull() ? null : Instant.ofEpochMilli(hb);
        return new ServiceRow(
                rs.getString("id"),
                rs.getString("team"),
                rs.getString("tier"),
                rs.getString("region"),
                heartbeat);
    };
}
