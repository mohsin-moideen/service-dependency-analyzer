package com.groupon.sda.graph.algorithms;

import com.groupon.sda.domain.graph.Edge;
import com.groupon.sda.domain.graph.ServiceNode;
import com.groupon.sda.graph.ServiceGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Lowest-latency path via Dijkstra's algorithm, weighted by the rolling average latency
 * of each edge ({@link Edge#rollingAvgLatencyMs()}).
 *
 * <p>Latencies are non-negative by construction so Dijkstra is correct (no need for
 * Bellman-Ford). Re-decrease-key isn't directly supported by {@link PriorityQueue};
 * we add a stale-skip check on pop, which is the standard idiom and asymptotically
 * equivalent.
 *
 * <p>If either endpoint is unknown, returns {@code null}. If they exist but no path
 * connects them, returns a {@link Result} with {@code found = false}.
 */
public final class ShortestPath {

    private ShortestPath() {
    }

    public record Result(String source, String target, boolean found,
                         List<String> path, double totalLatencyMs) {
    }

    public static Result shortestPath(ServiceGraph graph, String source, String target) {
        return graph.withReadLock(nodes -> dijkstra(nodes, source, target));
    }

    private static Result dijkstra(Map<String, ServiceNode> nodes, String source, String target) {
        if (source == null || target == null
                || !nodes.containsKey(source) || !nodes.containsKey(target)) {
            return null;
        }
        if (source.equals(target)) {
            return new Result(source, target, true, List.of(source), 0.0);
        }

        Map<String, Double> dist = new HashMap<>();
        Map<String, String> parent = new HashMap<>();
        dist.put(source, 0.0);

        PriorityQueue<Entry> pq = new PriorityQueue<>(Comparator.comparingDouble(Entry::dist));
        pq.add(new Entry(source, 0.0));

        while (!pq.isEmpty()) {
            Entry head = pq.poll();
            // Skip stale entries left over from earlier (smaller-dist) inserts.
            if (head.dist() > dist.getOrDefault(head.node(), Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (head.node().equals(target)) {
                break;
            }
            ServiceNode node = nodes.get(head.node());
            for (Map.Entry<String, Edge> e : node.outgoingView().entrySet()) {
                String v = e.getKey();
                double w = e.getValue().rollingAvgLatencyMs();
                double alt = head.dist() + w;
                Double dv = dist.get(v);
                if (dv == null || alt < dv) {
                    dist.put(v, alt);
                    parent.put(v, head.node());
                    pq.add(new Entry(v, alt));
                }
            }
        }

        if (!dist.containsKey(target)) {
            return new Result(source, target, false, List.of(), 0.0);
        }

        List<String> path = new ArrayList<>();
        String cur = target;
        while (cur != null) {
            path.add(cur);
            cur = parent.get(cur);
        }
        Collections.reverse(path);
        return new Result(source, target, true, path, dist.get(target));
    }

    private record Entry(String node, double dist) {
    }
}
