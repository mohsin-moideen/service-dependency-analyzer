package com.groupon.sda.persistence;

import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeSampleRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");

    @Test
    void insertAndFindRecentReturnsRowsAfterCutoff() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        EdgeSampleRepository repo = new EdgeSampleRepository(jdbc);

        repo.insert("a", "b", T0,                  10, Status.ok);
        repo.insert("a", "b", T0.plusSeconds(60),  20, Status.error);
        repo.insert("a", "b", T0.plusSeconds(120), 30, Status.ok);

        var recent = repo.findRecent(T0.plusSeconds(30).toEpochMilli());
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).sample().latencyMs()).isEqualTo(20);   // ts ASC
        assertThat(recent.get(1).sample().latencyMs()).isEqualTo(30);
    }

    @Test
    void findRecentIncludesSamplesExactlyOnCutoff() {
        // Symmetric with Edge.evictByAge (which uses isBefore — strictly before is gone).
        // A sample whose ts equals the cutoff must survive both prune and find, so a
        // restart re-loads it into memory.
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        EdgeSampleRepository repo = new EdgeSampleRepository(jdbc);

        repo.insert("a", "b", T0, 10, Status.ok);

        var recent = repo.findRecent(T0.toEpochMilli());
        assertThat(recent).hasSize(1);
    }

    @Test
    void pruneDeletesRowsBeforeCutoff() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        EdgeSampleRepository repo = new EdgeSampleRepository(jdbc);

        repo.insert("a", "b", T0,                  10, Status.ok);
        repo.insert("a", "b", T0.plusSeconds(60),  20, Status.ok);
        repo.insert("a", "b", T0.plusSeconds(120), 30, Status.ok);

        int deleted = repo.pruneOlderThan(T0.plusSeconds(90).toEpochMilli());
        assertThat(deleted).isEqualTo(2);
        var surviving = repo.findRecent(0);
        assertThat(surviving).hasSize(1);
        assertThat(surviving.get(0).sample().latencyMs()).isEqualTo(30);
    }
}
