package com.groupon.sda.graph.algorithms;

import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.graph.ServiceGraph;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ShortestPathTest {

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static ServiceGraph graph() {
        return new ServiceGraph(64, Duration.ofMinutes(5), CLOCK);
    }

    private static void edge(ServiceGraph g, String s, String t, int latency) {
        g.applyDependencyObserved(s, t, T0, latency, Status.ok);
    }

    @Test
    void shortestPathDirectEdge() {
        ServiceGraph g = graph();
        edge(g, "a", "b", 42);

        ShortestPath.Result r = ShortestPath.shortestPath(g, "a", "b");
        assertThat(r).isNotNull();
        assertThat(r.found()).isTrue();
        assertThat(r.path()).containsExactly("a", "b");
        assertThat(r.totalLatencyMs()).isEqualTo(42.0);
    }

    @Test
    void shortestPathChoosesLowerLatencyDetour() {
        // a -> b (100ms), a -> c -> b (10 + 10 = 20ms)
        ServiceGraph g = graph();
        edge(g, "a", "b", 100);
        edge(g, "a", "c", 10);
        edge(g, "c", "b", 10);

        ShortestPath.Result r = ShortestPath.shortestPath(g, "a", "b");
        assertThat(r.found()).isTrue();
        assertThat(r.path()).containsExactly("a", "c", "b");
        assertThat(r.totalLatencyMs()).isEqualTo(20.0);
    }

    @Test
    void shortestPathSourceEqualsTarget() {
        ServiceGraph g = graph();
        edge(g, "a", "b", 1);
        ShortestPath.Result r = ShortestPath.shortestPath(g, "a", "a");
        assertThat(r.found()).isTrue();
        assertThat(r.path()).containsExactly("a");
        assertThat(r.totalLatencyMs()).isEqualTo(0.0);
    }

    @Test
    void shortestPathNoConnection() {
        ServiceGraph g = graph();
        edge(g, "a", "b", 1);
        edge(g, "x", "y", 1);

        ShortestPath.Result r = ShortestPath.shortestPath(g, "a", "y");
        assertThat(r).isNotNull();
        assertThat(r.found()).isFalse();
        assertThat(r.path()).isEmpty();
    }

    @Test
    void shortestPathUnknownEndpointReturnsNull() {
        ServiceGraph g = graph();
        edge(g, "a", "b", 1);
        assertThat(ShortestPath.shortestPath(g, "a", "ghost")).isNull();
        assertThat(ShortestPath.shortestPath(g, "ghost", "b")).isNull();
        assertThat(ShortestPath.shortestPath(g, null, "b")).isNull();
    }

    @Test
    void shortestPathDiamondMatchesFixture() {
        // topology/02-diamond.json — a→b 10, a→c 50, b→d 10, c→d 5.
        // Expected: a→b→d wins at weight 20 over a→c→d at 55.
        ServiceGraph g = graph();
        edge(g, "a", "b", 10);
        edge(g, "a", "c", 50);
        edge(g, "b", "d", 10);
        edge(g, "c", "d", 5);

        ShortestPath.Result r = ShortestPath.shortestPath(g, "a", "d");
        assertThat(r).isNotNull();
        assertThat(r.found()).isTrue();
        assertThat(r.path()).containsExactly("a", "b", "d");
        assertThat(r.totalLatencyMs()).isEqualTo(20.0);
    }

    @Test
    void shortestPathIgnoresReverseEdgeDirection() {
        // a <- b only; can't go a -> b
        ServiceGraph g = graph();
        edge(g, "b", "a", 5);

        ShortestPath.Result r = ShortestPath.shortestPath(g, "a", "b");
        assertThat(r.found()).isFalse();
    }
}
