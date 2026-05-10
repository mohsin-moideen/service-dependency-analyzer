package com.groupon.sda.graph.algorithms;

import com.groupon.sda.domain.graph.ServiceNode;
import com.groupon.sda.graph.ServiceGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Top-{@code k} services by a hybrid criticality score.
 *
 * <h2>Metric</h2>
 * {@code score(v) = inDegree(v) × outDegree(v)}.
 *
 * <p>Intuition: a service that is both heavily depended upon (high in-degree) and
 * heavily depends on others (high out-degree) sits on the most call paths. Removing
 * such a service severs the most pairs of services from each other.
 *
 * <p>This is the take-home choice over Brandes' betweenness centrality. Trade-offs:
 * <ul>
 *   <li><b>Cost:</b> O(V) here vs. O(V·E) for Brandes — at 10k services / 100k edges,
 *       betweenness is ~1B operations and runs in seconds; this runs in microseconds.</li>
 *   <li><b>Accuracy:</b> betweenness is the textbook answer to "which nodes lie on the
 *       most shortest paths between all pairs." This hybrid is a coarse approximation —
 *       it doesn't account for global path structure. Hub services with many neighbours
 *       on each side score correctly; corner cases (e.g., a chokepoint that routes
 *       traffic between two large components but has few direct neighbours) score
 *       lower than they "should."</li>
 *   <li><b>Determinism:</b> ties are broken by service id (lexicographic) for stable
 *       output.</li>
 * </ul>
 * The report calls out betweenness as the natural next step.
 */
public final class Criticality {

    private Criticality() {
    }

    public record CriticalService(String id, double score, int inDegree, int outDegree) {
    }

    public record Result(int k, List<CriticalService> services) {
    }

    public static Result criticalServices(ServiceGraph graph, int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0, got " + k);
        }
        return graph.withReadLock(nodes -> compute(nodes, k));
    }

    private static Result compute(Map<String, ServiceNode> nodes, int k) {
        List<CriticalService> all = new ArrayList<>(nodes.size());
        for (ServiceNode n : nodes.values()) {
            int outDeg = n.outgoingView().size();
            int inDeg = n.incomingView().size();
            double score = (double) inDeg * outDeg;
            all.add(new CriticalService(n.id(), score, inDeg, outDeg));
        }
        all.sort(Comparator
                .comparingDouble(CriticalService::score).reversed()
                .thenComparing(CriticalService::id));   // stable tiebreak
        int actualK = Math.min(k, all.size());
        return new Result(k, new ArrayList<>(all.subList(0, actualK)));
    }
}
