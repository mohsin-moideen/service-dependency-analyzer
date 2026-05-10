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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cycles in the dependency graph, returned as one explicit cycle path per SCC.
 *
 * <h2>Approach</h2>
 * <ol>
 *   <li>Run Tarjan's strongly-connected-components algorithm on the directed graph.
 *       Iterative — recursive Tarjan stack-overflows on long chains in dependency
 *       graphs that don't even feel that big.</li>
 *   <li>For each SCC of size {@code > 1}: pick any internal directed edge {@code u → v},
 *       BFS from {@code v} back to {@code u} within the SCC, return
 *       {@code [u, v, ..., u]}.</li>
 *   <li>For each SCC of size 1 with a self-loop ({@code v → v}): emit {@code [v, v]}.</li>
 * </ol>
 *
 * <p>Multi-edge cycles are deliberately reported as one path per SCC, not as every
 * elementary cycle (Johnson's algorithm). The spec asks for "all dependency cycles
 * currently present" and for incident response that almost always means "is there a
 * cycle through service X, and what does it look like?" — one canonical example per
 * SCC suffices.
 */
public final class Cycles {

    private Cycles() {
    }

    public record Result(List<List<String>> cycles) {
    }

    public static Result cycles(ServiceGraph graph) {
        return graph.withReadLock(Cycles::compute);
    }

    private static Result compute(Map<String, ServiceNode> nodes) {
        List<List<String>> sccs = tarjan(nodes);
        // HashMap iteration in Tarjan gives non-deterministic SCC ordering. Sort by the
        // smallest node id within each SCC so /graph/cycles is stable across calls.
        sccs.sort(Comparator.comparing(scc ->
                scc.stream().min(Comparator.naturalOrder()).orElse("")));
        List<List<String>> cycles = new ArrayList<>();
        for (List<String> scc : sccs) {
            if (scc.size() == 1) {
                String v = scc.get(0);
                if (nodes.get(v).outgoingView().containsKey(v)) {
                    cycles.add(List.of(v, v));
                }
            } else {
                List<String> cyc = extractCycleFromScc(scc, nodes);
                if (cyc != null) {
                    cycles.add(cyc);
                }
            }
        }
        return new Result(cycles);
    }

    // ---- Tarjan's SCC, iterative ---------------------------------------------------

    private static List<List<String>> tarjan(Map<String, ServiceNode> nodes) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowlink = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> sccStack = new ArrayDeque<>();
        int[] counter = {0};
        List<List<String>> sccs = new ArrayList<>();

        for (String start : nodes.keySet()) {
            if (index.containsKey(start)) continue;
            strongConnectIterative(start, nodes, index, lowlink, onStack, sccStack, counter, sccs);
        }
        return sccs;
    }

    /** A frame in the simulated call stack. */
    private static final class Frame {
        final String v;
        final Iterator<String> neighbors;
        String pendingChild;   // if non-null, we just returned from a recursive call to this child

        Frame(String v, Iterator<String> neighbors) {
            this.v = v;
            this.neighbors = neighbors;
        }
    }

    private static void strongConnectIterative(String root,
                                               Map<String, ServiceNode> nodes,
                                               Map<String, Integer> index,
                                               Map<String, Integer> lowlink,
                                               Set<String> onStack,
                                               Deque<String> sccStack,
                                               int[] counter,
                                               List<List<String>> sccs) {
        Deque<Frame> callStack = new ArrayDeque<>();
        index.put(root, counter[0]);
        lowlink.put(root, counter[0]);
        counter[0]++;
        sccStack.push(root);
        onStack.add(root);
        callStack.push(new Frame(root, nodes.get(root).outgoingView().keySet().iterator()));

        while (!callStack.isEmpty()) {
            Frame top = callStack.peek();

            // If we just returned from a recursive call, fold the child's lowlink in.
            if (top.pendingChild != null) {
                lowlink.put(top.v, Math.min(lowlink.get(top.v), lowlink.get(top.pendingChild)));
                top.pendingChild = null;
            }

            // Walk neighbours.
            boolean recursed = false;
            while (top.neighbors.hasNext()) {
                String w = top.neighbors.next();
                if (!index.containsKey(w)) {
                    index.put(w, counter[0]);
                    lowlink.put(w, counter[0]);
                    counter[0]++;
                    sccStack.push(w);
                    onStack.add(w);
                    top.pendingChild = w;
                    callStack.push(new Frame(w, nodes.get(w).outgoingView().keySet().iterator()));
                    recursed = true;
                    break;
                } else if (onStack.contains(w)) {
                    lowlink.put(top.v, Math.min(lowlink.get(top.v), index.get(w)));
                }
            }
            if (recursed) continue;

            // No more neighbours — pop. If v is the root of an SCC, peel it off.
            if (lowlink.get(top.v).equals(index.get(top.v))) {
                List<String> scc = new ArrayList<>();
                String w;
                do {
                    w = sccStack.pop();
                    onStack.remove(w);
                    scc.add(w);
                } while (!w.equals(top.v));
                sccs.add(scc);
            }
            callStack.pop();
        }
    }

    // ---- Cycle extraction within one SCC --------------------------------------------

    private static List<String> extractCycleFromScc(List<String> sccNodes,
                                                    Map<String, ServiceNode> nodes) {
        Set<String> sccSet = new HashSet<>(sccNodes);

        // Sort the SCC nodes so the choice of "first internal edge" is stable across
        // calls. Without this, hash-map iteration would pick differently per JVM run.
        List<String> sortedNodes = new ArrayList<>(sccNodes);
        Collections.sort(sortedNodes);

        // Find the lex-smallest non-self-loop directed edge u -> v inside the SCC.
        String u = null, v = null;
        outer:
        for (String n : sortedNodes) {
            List<String> outgoing = new ArrayList<>(nodes.get(n).outgoingView().keySet());
            Collections.sort(outgoing);
            for (String t : outgoing) {
                if (sccSet.contains(t) && !t.equals(n)) {
                    u = n;
                    v = t;
                    break outer;
                }
            }
        }
        if (u == null) return null;   // can't happen for a real SCC of size > 1

        // BFS from v back to u, restricted to the SCC. Visit neighbours in sorted
        // order so the path reconstruction is also stable.
        Map<String, String> parent = new HashMap<>();
        parent.put(v, null);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(v);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (cur.equals(u)) break;
            List<String> neighbours = new ArrayList<>(nodes.get(cur).outgoingView().keySet());
            Collections.sort(neighbours);
            for (String n : neighbours) {
                if (!sccSet.contains(n)) continue;
                if (parent.containsKey(n)) continue;
                parent.put(n, cur);
                queue.add(n);
            }
        }
        if (!parent.containsKey(u)) return null;

        // Reconstruct v -> ... -> u, then prepend u to close the cycle.
        List<String> back = new ArrayList<>();
        String cur = u;
        while (cur != null) {
            back.add(cur);
            cur = parent.get(cur);
        }
        Collections.reverse(back);   // [v, ..., u]
        List<String> cycle = new ArrayList<>(back.size() + 1);
        cycle.add(u);
        cycle.addAll(back);          // [u, v, ..., u]
        return cycle;
    }
}
