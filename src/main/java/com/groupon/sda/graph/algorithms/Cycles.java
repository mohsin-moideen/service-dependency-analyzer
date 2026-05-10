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
 * All elementary cycles in the dependency graph.
 *
 * <h2>Approach</h2>
 * <ol>
 *   <li>Run iterative Tarjan's SCC algorithm (recursive Tarjan stack-overflows on long
 *       chains in dependency graphs that aren't even unusually large).</li>
 *   <li>For each SCC of size {@code > 1}, enumerate every elementary cycle by DFS:
 *       from each vertex {@code v} in the SCC sorted lexicographically, walk the
 *       subgraph induced by the SCC but <i>only consider neighbours whose id is
 *       lex ≥ v</i>. Every elementary cycle has a unique lex-smallest vertex; the
 *       constraint guarantees we discover each cycle exactly once at that vertex.</li>
 *   <li>For each SCC of size 1 with a self-loop, emit {@code [v, v]}.</li>
 * </ol>
 *
 * <p>This is a simpler relative of Johnson's algorithm. Johnson's "remove vertex,
 * recompute SCCs" outer loop is one way to ensure each elementary cycle is found
 * exactly once; the lex-min constraint here is another way that's lighter to
 * implement and equally correct on directed graphs. Worst-case complexity is the
 * same as Johnson's — O((V+E)·(C+1)) where C is the number of elementary cycles —
 * which can blow up on dense SCCs. Real dependency graphs have small, sparse
 * cycles, so it's microseconds in practice.
 *
 * <p>Output is sorted: by cycle length ascending, then lexicographically by the
 * cycle's content. Stable across runs.
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
        List<List<String>> cycles = new ArrayList<>();
        for (List<String> scc : sccs) {
            if (scc.size() == 1) {
                String v = scc.get(0);
                if (nodes.get(v).outgoingView().containsKey(v)) {
                    cycles.add(List.of(v, v));
                }
            } else {
                cycles.addAll(enumerateElementaryCycles(scc, nodes));
            }
        }
        // Stable ordering: by cycle length, then lex.
        cycles.sort(Comparator
                .comparingInt(List<String>::size)
                .thenComparing(Cycles::lexCompare));
        return new Result(cycles);
    }

    private static int lexCompare(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }

    // ---- Elementary cycle enumeration ----------------------------------------------

    private static List<List<String>> enumerateElementaryCycles(List<String> sccNodes,
                                                                Map<String, ServiceNode> nodes) {
        List<String> sortedScc = new ArrayList<>(sccNodes);
        Collections.sort(sortedScc);
        Set<String> sccSet = new HashSet<>(sccNodes);
        List<List<String>> cycles = new ArrayList<>();

        for (String start : sortedScc) {
            // path stack holds the current DFS path with `start` at the bottom.
            Deque<String> path = new ArrayDeque<>();
            Set<String> onPath = new HashSet<>();
            path.push(start);
            onPath.add(start);
            dfsFromMin(start, nodes, sccSet, path, onPath, cycles);
            path.pop();
            onPath.remove(start);
        }
        return cycles;
    }

    private static void dfsFromMin(String start,
                                   Map<String, ServiceNode> nodes,
                                   Set<String> sccSet,
                                   Deque<String> path,
                                   Set<String> onPath,
                                   List<List<String>> cycles) {
        String current = path.peek();
        // Sort neighbours for determinism in cycle output.
        List<String> outNeighbors = new ArrayList<>(nodes.get(current).outgoingView().keySet());
        Collections.sort(outNeighbors);
        for (String next : outNeighbors) {
            if (!sccSet.contains(next)) continue;
            if (next.compareTo(start) < 0) continue;     // lex constraint
            if (next.equals(start)) {
                // Cycle closed back to start.
                List<String> cycle = new ArrayList<>(path.size() + 1);
                Iterator<String> it = path.descendingIterator();
                while (it.hasNext()) cycle.add(it.next());
                cycle.add(start);
                cycles.add(cycle);
            } else if (!onPath.contains(next)) {
                path.push(next);
                onPath.add(next);
                dfsFromMin(start, nodes, sccSet, path, onPath, cycles);
                path.pop();
                onPath.remove(next);
            }
        }
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

    private static final class Frame {
        final String v;
        final Iterator<String> neighbors;
        String pendingChild;

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
            if (top.pendingChild != null) {
                lowlink.put(top.v, Math.min(lowlink.get(top.v), lowlink.get(top.pendingChild)));
                top.pendingChild = null;
            }
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
}
