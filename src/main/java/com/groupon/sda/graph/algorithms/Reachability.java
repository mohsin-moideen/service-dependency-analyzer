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
 * which is also a shortest-by-hop-count path because BFS explores level by level.
 *
 * <p>Path direction follows the call graph in both queries:
 * <ul>
 *   <li>{@code reachable(A)} returns paths {@code [A, ..., X]} — A calls ... calls X.</li>
 *   <li>{@code dependents(A)} returns paths {@code [X, ..., A]} — X calls ... calls A.</li>
 * </ul>
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
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            ServiceNode node = nodes.get(current);
            Iterable<String> neighbors = reverse
                    ? node.incomingView()
                    : node.outgoingView().keySet();
            for (String n : neighbors) {
                if (visited.add(n)) {
                    parent.put(n, current);
                    queue.add(n);
                }
            }
        }

        List<ReachableNode> result = new ArrayList<>(visited.size() - 1);
        for (String node : visited) {
            if (node.equals(start)) continue;
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
     * Reconstruct the call-direction path for a visited node.
     *
     * <p>For forward BFS from {@code start}: parent pointers walk back from {@code node}
     * to {@code start}, so we reverse to get {@code [start, ..., node]}.
     *
     * <p>For reverse BFS from {@code start}: parent pointers also walk back from
     * {@code node} to {@code start}, but the call direction in the original graph is
     * {@code node -> ... -> start}, which is exactly the unreversed parent chain.
     */
    private static List<String> buildPath(String node, Map<String, String> parent,
                                          String start, boolean reverse) {
        List<String> path = new ArrayList<>();
        String cur = node;
        while (cur != null) {
            path.add(cur);
            cur = parent.get(cur);
        }
        if (!reverse) {
            Collections.reverse(path);
        }
        return path;
    }
}
