package com.groupon.sda.persistence;

import com.groupon.sda.config.HealthProperties;
import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.domain.graph.Edge;
import com.groupon.sda.graph.ServiceGraph;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRestoreRunnerTest {

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    @Test
    void restorePopulatesGraphFromAllThreeTables() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        ServiceRepository services = new ServiceRepository(jdbc);
        EdgeRepository edges = new EdgeRepository(jdbc);
        EdgeSampleRepository samples = new EdgeSampleRepository(jdbc);

        // Pre-populate "as if" the previous run had committed these rows.
        services.upsertMetadata("checkout", "team-a", "tier-1", "us-east");
        services.upsertHeartbeat("checkout", T0);
        services.upsertMetadata("payments", "team-b", null, "us-east");

        edges.upsert("checkout", "payments", 42.0, 100L, T0);

        samples.insert("checkout", "payments", T0,                10, Status.ok);
        samples.insert("checkout", "payments", T0.plusSeconds(1), 20, Status.error);
        // Stale sample — should be excluded by the runner's findRecent cutoff.
        samples.insert("checkout", "payments", T0.minusSeconds(3_600), 99, Status.ok);

        // Window of 5 min, ageCap 5 min; current clock is at T0, so cutoff = T0 - 5min.
        // The stale sample at T0 - 1h is older than cutoff → excluded.
        ServiceGraph graph = new ServiceGraph(1024, Duration.ofMinutes(5), CLOCK);
        HealthProperties health = new HealthProperties(300, 1024);

        new GraphRestoreRunner(services, edges, samples, graph, CLOCK, health).restore();

        // Topology
        assertThat(graph.hasNode("checkout")).isTrue();
        assertThat(graph.hasNode("payments")).isTrue();
        assertThat(graph.edgeCount()).isEqualTo(1);
        ServiceGraph.Snapshot snap = graph.structuralSnapshot();
        assertThat(snap.outgoing().get("checkout"))
                .containsEntry("payments", 42.0);

        // Health: 2 in-window samples restored, 1 ok + 1 error = 0.5 error rate
        Edge.HealthSnapshot health2 = graph.computeHealth("checkout", T0.plusSeconds(2),
                Duration.ofMinutes(5));
        assertThat(health2).isNotNull();
        assertThat(health2.sampleCount()).isEqualTo(2);
        assertThat(health2.errorRate()).isEqualTo(0.5);
    }

    @Test
    void restoreOnEmptyDatabaseLeavesGraphEmpty() {
        JdbcTemplate jdbc = PersistenceTestSupport.freshDatabase();
        ServiceRepository services = new ServiceRepository(jdbc);
        EdgeRepository edges = new EdgeRepository(jdbc);
        EdgeSampleRepository samples = new EdgeSampleRepository(jdbc);

        ServiceGraph graph = new ServiceGraph(1024, Duration.ofMinutes(5), CLOCK);
        HealthProperties health = new HealthProperties(300, 1024);

        new GraphRestoreRunner(services, edges, samples, graph, CLOCK, health).restore();

        assertThat(graph.nodeCount()).isZero();
        assertThat(graph.edgeCount()).isZero();
    }
}
