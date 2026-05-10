package com.groupon.sda.graph.algorithms;

import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.graph.ServiceGraph;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CyclesTest {

    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static ServiceGraph graph() {
        return new ServiceGraph(64, Duration.ofMinutes(5), CLOCK);
    }

    private static void edge(ServiceGraph g, String s, String t) {
        g.applyDependencyObserved(s, t, T0, 1, Status.ok);
    }

    @Test
    void acyclicGraphHasNoCycles() {
        ServiceGraph g = graph();
        edge(g, "a", "b");
        edge(g, "b", "c");

        Cycles.Result r = Cycles.cycles(g);
        assertThat(r.cycles()).isEmpty();
    }

    @Test
    void twoNodeCycleIsReported() {
        ServiceGraph g = graph();
        edge(g, "a", "b");
        edge(g, "b", "a");

        Cycles.Result r = Cycles.cycles(g);
        assertThat(r.cycles()).hasSize(1);
        List<String> cycle = r.cycles().get(0);
        // Form: [u, v, u] where {u,v} = {"a","b"}.
        assertThat(cycle).hasSize(3);
        assertThat(cycle.get(0)).isEqualTo(cycle.get(2));
        assertThat(List.of(cycle.get(0), cycle.get(1)))
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void threeNodeCycleIsReported() {
        ServiceGraph g = graph();
        edge(g, "a", "b");
        edge(g, "b", "c");
        edge(g, "c", "a");

        Cycles.Result r = Cycles.cycles(g);
        assertThat(r.cycles()).hasSize(1);
        List<String> cycle = r.cycles().get(0);
        // Length 4 (3 distinct nodes plus the closing repeat).
        assertThat(cycle).hasSize(4);
        assertThat(cycle.get(0)).isEqualTo(cycle.get(cycle.size() - 1));
        // First three elements are a permutation of {a, b, c}.
        assertThat(cycle.subList(0, 3)).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void selfLoopIsReportedAsLengthTwo() {
        ServiceGraph g = graph();
        edge(g, "loop", "loop");

        Cycles.Result r = Cycles.cycles(g);
        assertThat(r.cycles()).hasSize(1);
        assertThat(r.cycles().get(0)).containsExactly("loop", "loop");
    }

    @Test
    void multipleSeparateCyclesAreAllReported() {
        ServiceGraph g = graph();
        // Cycle 1: a <-> b
        edge(g, "a", "b");
        edge(g, "b", "a");
        // Cycle 2: x -> y -> z -> x
        edge(g, "x", "y");
        edge(g, "y", "z");
        edge(g, "z", "x");
        // Acyclic chain: m -> n
        edge(g, "m", "n");

        Cycles.Result r = Cycles.cycles(g);
        assertThat(r.cycles()).hasSize(2);
    }

    @Test
    void cycleListIsDeterministicallyOrdered() {
        // Round-3 regression: HashMap iteration in Tarjan made cycle order non-stable.
        // The result list must now be sorted by the smallest node id within each SCC.
        ServiceGraph g = graph();
        // Cycle around "z" — starts with the lex-largest min-node.
        edge(g, "z1", "z2"); edge(g, "z2", "z1");
        // Cycle around "m"
        edge(g, "m1", "m2"); edge(g, "m2", "m1");
        // Cycle around "a"
        edge(g, "a1", "a2"); edge(g, "a2", "a1");

        Cycles.Result r = Cycles.cycles(g);
        assertThat(r.cycles()).hasSize(3);
        // Each cycle is [u, v, u]; the min of each is the SCC sort key.
        // We expect cycles whose min-node is "a*" first, then "m*", then "z*".
        assertThat(r.cycles().get(0)).contains("a1");
        assertThat(r.cycles().get(1)).contains("m1");
        assertThat(r.cycles().get(2)).contains("z1");
    }

    @Test
    void cycleEnumerationOrderIsDeterministicWithinScc() {
        // SCC {a,b,c} with edges a->b, b->c, c->a, b->a contains two elementary
        // cycles: [a,b,a] and [a,b,c,a]. The new contract enumerates both; this
        // test pins the output ORDER (shorter first, lex-asc within length) so the
        // /graph/cycles endpoint is stable across calls when the graph hasn't
        // changed. Without sorting in the algorithm, hash-map order would let the
        // pair come back in either order.
        ServiceGraph g = graph();
        edge(g, "a", "b");
        edge(g, "b", "c");
        edge(g, "c", "a");
        edge(g, "b", "a");

        Cycles.Result r = Cycles.cycles(g);
        assertThat(r.cycles()).containsExactly(
                List.of("a", "b", "a"),
                List.of("a", "b", "c", "a"));
    }

    @Test
    void singleSccProducesEveryElementaryCycle() {
        // SCC {a, b, c} contains two elementary cycles:
        //   - a → b → a   (via the b → a back-edge)
        //   - a → b → c → a
        // The new contract enumerates both.
        ServiceGraph g = graph();
        edge(g, "a", "b");
        edge(g, "b", "c");
        edge(g, "c", "a");
        edge(g, "b", "a");

        Cycles.Result r = Cycles.cycles(g);
        assertThat(r.cycles()).hasSize(2);
        // Sorted by length: 2-cycle [a,b,a] before 3-cycle [a,b,c,a].
        assertThat(r.cycles().get(0)).containsExactly("a", "b", "a");
        assertThat(r.cycles().get(1)).containsExactly("a", "b", "c", "a");
    }

    @Test
    void overlappingCyclesFixtureFindsBothElementaryCycles() {
        // topology/08-overlapping-cycles: 3-cycle a→b→c→a plus 2-cycle b↔d sharing b.
        // All four nodes form one SCC, but two distinct elementary cycles must be found.
        ServiceGraph g = graph();
        edge(g, "a", "b");
        edge(g, "b", "c");
        edge(g, "c", "a");
        edge(g, "b", "d");
        edge(g, "d", "b");

        Cycles.Result r = Cycles.cycles(g);
        assertThat(r.cycles()).hasSize(2);
        // Sort: by length, then lex. [b,d,b] (len 3) before [a,b,c,a] (len 4).
        assertThat(r.cycles().get(0)).containsExactly("b", "d", "b");
        assertThat(r.cycles().get(1)).containsExactly("a", "b", "c", "a");
    }
}
