package com.groupon.sda.graph.algorithms;

import com.groupon.sda.domain.graph.ServiceNode;
import com.groupon.sda.graph.ServiceGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forward and reverse reachability via BFS. The "reachable" query is the blast-radius
 * question (downstream); the "dependents" query is its mirror (upstream).
 *
 * <p>Each visited node is returned with one path — the one BFS happens to find first,
 * which is a shortest-by-hop-count path because BFS explores level by level.
 *
 * <p>Path direction follows the call graph in both queries:
 * <ul>
 *   <li>{@code reachable(A)} returns paths {@code [A, ..., X]} — A calls ... calls X.</li>
 *   <li>{@code dependents(A)} returns paths {@code [X, ..., A]} — X calls ... calls A.</li>
 * </ul>
 *
 * <p><b>{@code start} is included in its own result iff there's a path of length ≥ 1
 * back to it</b> — i.e., a self-loop or any cycle through {@code start}. The path in
 * that case walks the loop ({@code [a, a]} for a self-loop, {@code [a, b, ..., a]} for
 * longer cycles). This matches the spec's blast-radius semantics: a service whose
 * failure can recursively affect itself is part of its own blast radius.
 *
 * <p>{@link Result#service()} matches the caller's {@code start} argument; if the start
 * node isn't in the graph the function returns {@code null}.
 */
public final class Reachability {

    private Reachability() {
    }

    public record ReachableNode(String id, List<String> path) {
    }

    public record Result(String service, List<ReachableNode> reachable) {
    }

    /** Forward BFS — every service reachable downstream of {@code start}. */
    public static Result reachable(ServiceGraph graph, String start) {
        return graph.withReadLock(nodes -> bfs(nodes, start, false));
    }

    /** Reverse BFS — every service that transitively depends on {@code start}. */
    public static Result dependents(ServiceGraph graph, String start) {
        return graph.withReadLock(nodes -> bfs(nodes, start, true));
    }

    private static Result bfs(Map<String, ServiceNode> nodes, String start, boolean reverse) {
        if (start == null || !nodes.containsKey(start)) {
            return null;
        }
        // parent[X] = predecessor of X in the BFS — the node from which we *reached* X.
        Map<String, String> parent = new HashMap<>();
        // `reached` = nodes for which we've discovered a length-≥-1 path from start.
        // Distinct from `discovered` (queue dedup) because we want start to land in
        // `reached` only when an edge actually brings us back to it.
        Set<String> reached = new HashSet<>();
        Set<String> discovered = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        discovered.add(start);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            ServiceNode node = nodes.get(current);
            Iterable<String> neighbors = reverse
                    ? node.incomingView()
                    : node.outgoingView().keySet();
            for (String n : neighbors) {
                if (reached.add(n)) {
                    parent.put(n, current);
                }
                if (discovered.add(n)) {
                    queue.add(n);
                }
            }
        }

        List<ReachableNode> result = new ArrayList<>(reached.size());
        for (String node : reached) {
            List<String> path = buildPath(node, parent, start, reverse);
            result.add(new ReachableNode(node, path));
        }
        // HashSet iteration order is not stable across JVM runs. Sort by path length
        // (closer-first is a useful default for blast-radius UX) then by id for tiebreak.
        result.sort(Comparator
                .comparingInt((ReachableNode n) -> n.path().size())
                .thenComparing(ReachableNode::id));
        return new Result(start, result);
    }

    /**
     * Reconstruct the call-direction path for a reached node.
     *
     * <p>For nodes other than {@code start}: walk parent pointers until we hit
     * {@code start}, then stop. Forward-direction paths get reversed so callers see
     * {@code [start, ..., node]}.
     *
     * <p>For {@code node == start} (self-loop or cycle case): walk one full loop —
     * {@code start → parent[start] → ... → start}. Without the early termination on
     * {@code start} the {@code while} loop would never exit because {@code parent[start]}
     * eventually points back to {@code start}.
     */
    private static List<String> buildPath(String node, Map<String, String> parent,
                                          String start, boolean reverse) {
        List<String> path = new ArrayList<>();
        if (node.equals(start)) {
            path.add(start);
            String cur = parent.get(start);
            while (cur != null && !cur.equals(start)) {
                path.add(cur);
                cur = parent.get(cur);
            }
            path.add(start);   // close the cycle
        } else {
            String cur = node;
            while (cur != null && !cur.equals(start)) {
                path.add(cur);
                cur = parent.get(cur);
            }
            if (cur != null) path.add(start);
        }
        if (!reverse) {
            Collections.reverse(path);
        }
        return path;
    }
}
