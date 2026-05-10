package com.groupon.sda.graph.algorithms;

import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.graph.ServiceGraph;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CriticalityTest {

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static ServiceGraph graph() {
        return new ServiceGraph(64, Duration.ofMinutes(5), CLOCK);
    }

    private static void edge(ServiceGraph g, String s, String t) {
        g.applyDependencyObserved(s, t, T0, 1, Status.ok);
    }

    @Test
    void hubScoresHighestWithBalancedInAndOutDegree() {
        // A hub with 3 in, 3 out: score = 9. Leaves: 0 each.
        ServiceGraph g = graph();
        edge(g, "u1", "hub");
        edge(g, "u2", "hub");
        edge(g, "u3", "hub");
        edge(g, "hub", "d1");
        edge(g, "hub", "d2");
        edge(g, "hub", "d3");

        Criticality.Result r = Criticality.criticalServices(g, 3);
        assertThat(r.services()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(r.services().get(0).id()).isEqualTo("hub");
        assertThat(r.services().get(0).score()).isEqualTo(9.0);
        assertThat(r.services().get(0).inDegree()).isEqualTo(3);
        assertThat(r.services().get(0).outDegree()).isEqualTo(3);
    }

    @Test
    void purelyDownstreamServiceScoresZero() {
        // 'leaf' has 1 in, 0 out -> score 0. 'producer' has 0 in, 1 out -> score 0.
        ServiceGraph g = graph();
        edge(g, "producer", "leaf");

        Criticality.Result r = Criticality.criticalServices(g, 5);
        assertThat(r.services()).extracting("score").containsOnly(0.0);
    }

    @Test
    void resultLimitedToTopK() {
        ServiceGraph g = graph();
        // Three balanced hubs with different scores.
        // hub3: 3x3 = 9
        for (int i = 0; i < 3; i++) edge(g, "u3-" + i, "hub3");
        for (int i = 0; i < 3; i++) edge(g, "hub3", "d3-" + i);
        // hub2: 2x2 = 4
        for (int i = 0; i < 2; i++) edge(g, "u2-" + i, "hub2");
        for (int i = 0; i < 2; i++) edge(g, "hub2", "d2-" + i);
        // hub1: 1x1 = 1
        edge(g, "u1-0", "hub1");
        edge(g, "hub1", "d1-0");

        Criticality.Result r = Criticality.criticalServices(g, 2);
        assertThat(r.services()).hasSize(2);
        assertThat(r.services().get(0).id()).isEqualTo("hub3");
        assertThat(r.services().get(1).id()).isEqualTo("hub2");
    }

    @Test
    void kBiggerThanGraphReturnsAll() {
        ServiceGraph g = graph();
        edge(g, "a", "b");
        Criticality.Result r = Criticality.criticalServices(g, 100);
        assertThat(r.services()).hasSize(2);   // a and b
    }

    @Test
    void rejectsNonPositiveK() {
        ServiceGraph g = graph();
        edge(g, "a", "b");
        assertThatThrownBy(() -> Criticality.criticalServices(g, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Criticality.criticalServices(g, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tiesBrokenByIdLexicographically() {
        // Two nodes both with score 0 — order should be deterministic.
        ServiceGraph g = graph();
        edge(g, "z", "y");   // z: out=1, in=0; y: out=0, in=1; both score 0
        edge(g, "a", "b");   // similar

        Criticality.Result r = Criticality.criticalServices(g, 4);
        // Sorted by score desc (all 0), then id asc.
        assertThat(r.services()).extracting("id").containsExactly("a", "b", "y", "z");
    }
}
