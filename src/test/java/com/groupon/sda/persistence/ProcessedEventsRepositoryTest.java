package com.groupon.sda.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedEventsRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");

    @Test
    void insertReturnsTrueForNewIdAndFalseForDuplicate() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        ProcessedEventsRepository repo = new ProcessedEventsRepository(jdbc);

        assertThat(repo.insertIfAbsent("e1", T0)).isTrue();
        assertThat(repo.insertIfAbsent("e1", T0.plusSeconds(1))).isFalse();
        assertThat(repo.exists("e1")).isTrue();
        assertThat(repo.exists("nope")).isFalse();
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void manyDistinctIdsAreAllRecorded() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        ProcessedEventsRepository repo = new ProcessedEventsRepository(jdbc);

        for (int i = 0; i < 100; i++) {
            assertThat(repo.insertIfAbsent("e" + i, T0)).isTrue();
        }
        assertThat(repo.count()).isEqualTo(100);
    }
}
