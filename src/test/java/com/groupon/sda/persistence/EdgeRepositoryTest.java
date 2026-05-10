package com.groupon.sda.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeRepositoryTest {

    @Test
    void upsertInsertsThenUpdatesRollingStats() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        EdgeRepository repo = new EdgeRepository(jdbc);

        repo.upsert("a", "b", 10.0, 1L);
        assertThat(repo.findAll()).extracting("rollingAvgLatencyMs").containsExactly(10.0);

        repo.upsert("a", "b", 25.5, 5L);
        var rows = repo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rollingAvgLatencyMs()).isEqualTo(25.5);
        assertThat(rows.get(0).sampleCount()).isEqualTo(5L);
    }

    @Test
    void deleteRemovesOnlyTheSpecifiedEdge() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        EdgeRepository repo = new EdgeRepository(jdbc);

        repo.upsert("a", "b", 1.0, 1L);
        repo.upsert("a", "c", 2.0, 1L);
        repo.upsert("b", "c", 3.0, 1L);

        int deleted = repo.delete("a", "b");
        assertThat(deleted).isEqualTo(1);
        assertThat(repo.findAll()).hasSize(2);

        // Deleting a non-existent edge is a no-op.
        assertThat(repo.delete("ghost", "tgt")).isZero();
    }
}
