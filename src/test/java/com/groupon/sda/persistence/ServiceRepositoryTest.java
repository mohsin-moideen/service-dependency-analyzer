package com.groupon.sda.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");

    @Test
    void upsertMetadataInsertsThenUpdates() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        ServiceRepository repo = new ServiceRepository(jdbc);

        repo.upsertMetadata("svc", "team-a", "tier-1", "us-east");
        var rows = repo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).team()).isEqualTo("team-a");
        assertThat(rows.get(0).tier()).isEqualTo("tier-1");
        assertThat(rows.get(0).region()).isEqualTo("us-east");

        // Partial update — null fields should preserve previous values.
        repo.upsertMetadata("svc", null, "tier-0", null);
        rows = repo.findAll();
        assertThat(rows.get(0).team()).isEqualTo("team-a");      // preserved
        assertThat(rows.get(0).tier()).isEqualTo("tier-0");      // updated
        assertThat(rows.get(0).region()).isEqualTo("us-east");   // preserved
    }

    @Test
    void upsertHeartbeatStoresAndKeepsLatest() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        ServiceRepository repo = new ServiceRepository(jdbc);

        repo.upsertHeartbeat("svc", T0.plusSeconds(60));
        repo.upsertHeartbeat("svc", T0);   // older — must be ignored

        var rows = repo.findAll();
        assertThat(rows.get(0).lastHeartbeat()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void ensureCreatesRowWithoutMetadata() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        ServiceRepository repo = new ServiceRepository(jdbc);

        repo.ensure("svc");
        var rows = repo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).team()).isNull();
        assertThat(rows.get(0).lastHeartbeat()).isNull();
    }
}
