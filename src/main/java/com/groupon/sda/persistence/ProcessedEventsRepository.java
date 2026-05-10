package com.groupon.sda.persistence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Durable idempotency layer. Every successful event commit also inserts a row here,
 * keyed by {@code event_id}. The PRIMARY KEY constraint is the second line of defence
 * after {@code SeenEventsCache}: even if the cache says "definitely new" because the
 * event id wasn't seen since last restart, this repository's
 * {@link #insertIfAbsent} returns {@code false} when the row already exists, and
 * the consumer rolls back its transaction.
 */
@Repository
public class ProcessedEventsRepository {

    private final JdbcTemplate jdbc;

    @Autowired
    public ProcessedEventsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return {@code true} if this is the first time we've seen {@code eventId};
     *         {@code false} if it was already recorded (i.e., the event is a duplicate).
     */
    public boolean insertIfAbsent(String eventId, Instant processedTs) {
        int rows = jdbc.update("""
                INSERT INTO processed_events(event_id, processed_ts)
                VALUES (?, ?)
                ON CONFLICT(event_id) DO NOTHING
                """, eventId, processedTs.toEpochMilli());
        return rows == 1;
    }

    public boolean exists(String eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class, eventId);
        return count != null && count > 0;
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM processed_events", Long.class);
        return n == null ? 0L : n;
    }
}
