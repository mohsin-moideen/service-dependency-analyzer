package com.groupon.sda.graph.algorithms;

import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.graph.ServiceGraph;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ReachabilityTest {

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static ServiceGraph graph() {
        return new ServiceGraph(64, Duration.ofMinutes(5), CLOCK);
    }

    private static void edge(ServiceGraph g, String s, String t) {
        g.applyDependencyObserved(s, t, T0, 10, Status.ok);
    }

    @Test
    void reachableLinearChain() {
        // a -> b -> c -> d
        ServiceGraph g = graph();
        edge(g, "a", "b");
        edge(g, "b", "c");
        edge(g, "c", "d");

        Reachability.Result r = Reachability.reachable(g, "a");
        assertThat(r).isNotNull();
        assertThat(r.service()).isEqualTo("a");
        assertThat(r.reachable()).extracting("id")
                .containsExactlyInAnyOrder("b", "c", "d");

        // Path for "d" should be a -> b -> c -> d, in call direction.
        var dEntry = r.reachable().stream().filter(n -> n.id().equals("d")).findFirst().orElseThrow();
        assertThat(dEntry.path()).containsExactly("a", "b", "c", "d");
    }

    @Test
    void dependentsLinearChain() {
        // a -> b -> c -> d : who depends on c?
        ServiceGraph g = graph();
        edge(g, "a", "b");
        edge(g, "b", "c");
        edge(g, "c", "d");

        Reachability.Result r = Reachability.dependents(g, "c");
        assertThat(r).isNotNull();
        assertThat(r.reachable()).extracting("id")
                .containsExactlyInAnyOrder("a", "b");

        // Path for "a" should be a -> b -> c (call direction toward c).
        var aEntry = r.reachable().stream().filter(n -> n.id().equals("a")).findFirst().orElseThrow();
        assertThat(aEntry.path()).containsExactly("a", "b", "c");
    }

    @Test
    void reachableFanOut() {
        // hub -> {a, b, c}
        ServiceGraph g = graph();
        edge(g, "hub", "a");
        edge(g, "hub", "b");
        edge(g, "hub", "c");

        Reachability.Result r = Reachability.reachable(g, "hub");
        assertThat(r.reachable()).hasSize(3);
        for (var node : r.reachable()) {
            assertThat(node.path()).containsExactly("hub", node.id());
        }
    }

    @Test
    void reachableUnknownServiceReturnsNull() {
        ServiceGraph g = graph();
        edge(g, "a", "b");
        assertThat(Reachability.reachable(g, "ghost")).isNull();
        assertThat(Reachability.dependents(g, "ghost")).isNull();
    }

    @Test
    void reachableIsolatedNodeYieldsEmptyReachable() {
        ServiceGraph g = graph();
        g.applyHeartbeat("alone", T0);   // creates the node, no edges

        Reachability.Result r = Reachability.reachable(g, "alone");
        assertThat(r).isNotNull();
        assertThat(r.reachable()).isEmpty();
    }

    @Test
    void reachableDoesNotLoopOnCycles() {
        // a -> b -> c -> a
        ServiceGraph g = graph();
        edge(g, "a", "b");
        edge(g, "b", "c");
        edge(g, "c", "a");

        Reachability.Result r = Reachability.reachable(g, "a");
        // 'a' itself isn't in the result; b and c are.
        assertThat(r.reachable()).extracting("id").containsExactlyInAnyOrder("b", "c");
    }

    @Test
    void reachableDisconnectedComponentsAreNotReached() {
        ServiceGraph g = graph();
        edge(g, "a", "b");
        edge(g, "x", "y");

        Reachability.Result r = Reachability.reachable(g, "a");
        assertThat(r.reachable()).extracting("id").containsExactly("b");
    }

    @Test
    void reachableNullStartReturnsNull() {
        ServiceGraph g = graph();
        edge(g, "a", "b");
        assertThat(Reachability.reachable(g, null)).isNull();
    }

    @Test
    void reachableResultIsDeterministicallyOrdered() {
        // Round-3 regression: HashSet iteration order made the result list non-stable
        // across calls. Output must now be sorted by path length, then by id.
        // Build a graph where siblings at the same depth have ids in non-alphabetical
        // insertion order, so a stable sort can be observed independent of insertion.
        ServiceGraph g = graph();
        edge(g, "root", "z-near");
        edge(g, "root", "a-near");
        edge(g, "z-near", "z-far");
        edge(g, "a-near", "a-far");

        Reachability.Result r = Reachability.reachable(g, "root");
        // Depth-1 nodes first (a-near, z-near), then depth-2 (a-far, z-far),
        // each tier sorted by id.
        assertThat(r.reachable()).extracting("id")
                .containsExactly("a-near", "z-near", "a-far", "z-far");
    }
}
