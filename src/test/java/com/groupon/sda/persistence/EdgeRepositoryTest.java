package com.groupon.sda.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");

    @Test
    void upsertInsertsThenUpdatesRollingStats() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        EdgeRepository repo = new EdgeRepository(jdbc);

        repo.upsert("a", "b", 10.0, 1L, T0);
        assertThat(repo.findAll()).extracting("rollingAvgLatencyMs").containsExactly(10.0);

        repo.upsert("a", "b", 25.5, 5L, T0.plusSeconds(60));
        var rows = repo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rollingAvgLatencyMs()).isEqualTo(25.5);
        assertThat(rows.get(0).sampleCount()).isEqualTo(5L);
        assertThat(rows.get(0).lastObservedTs()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void upsertKeepsHigherSampleCountUnderConflict() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        EdgeRepository repo = new EdgeRepository(jdbc);

        // First write (winner).
        repo.upsert("a", "b", 25.0, 5L, T0.plusSeconds(60));
        // Second write with stale stats — should not overwrite.
        repo.upsert("a", "b", 10.0, 1L, T0);

        var rows = repo.findAll();
        assertThat(rows.get(0).sampleCount()).isEqualTo(5L);
        assertThat(rows.get(0).rollingAvgLatencyMs()).isEqualTo(25.0);
        assertThat(rows.get(0).lastObservedTs()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void deleteRemovesOnlyTheSpecifiedEdge() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        EdgeRepository repo = new EdgeRepository(jdbc);

        repo.upsert("a", "b", 1.0, 1L, T0);
        repo.upsert("a", "c", 2.0, 1L, T0);
        repo.upsert("b", "c", 3.0, 1L, T0);

        int deleted = repo.delete("a", "b");
        assertThat(deleted).isEqualTo(1);
        assertThat(repo.findAll()).hasSize(2);

        // Deleting a non-existent edge is a no-op.
        assertThat(repo.delete("ghost", "tgt")).isZero();
    }
}
