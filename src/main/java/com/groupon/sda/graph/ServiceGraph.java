package com.groupon.sda.graph;

import com.groupon.sda.domain.event.DependencyObservedEvent.Status;
import com.groupon.sda.domain.graph.Edge;
import com.groupon.sda.domain.graph.EdgeStats;
import com.groupon.sda.domain.graph.Sample;
import com.groupon.sda.domain.graph.ServiceNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * In-memory directed graph of services and dependencies.
 *
 * <h2>Representation</h2>
 * Adjacency list with both directions maintained: each {@link ServiceNode} keeps a map of
 * outgoing {@link Edge}s and a set of incoming source ids. Edge objects are owned by the
 * source's outgoing map; reverse traversals look up the actual edge via the source node.
 *
 * <h2>Concurrency</h2>
 * A single {@link ReentrantReadWriteLock} guards all state. Every mutation runs under the
 * write lock; every read runs under the read lock. The internal node, edge, and sample
 * containers are <b>not</b> concurrent collections — the lock provides happens-before for
 * every observation.
 *
 * <p>For long-running graph algorithms (notably betweenness centrality on the full graph)
 * use {@link #structuralSnapshot()} to copy adjacency under the read lock, then run
 * lock-free against the snapshot.
 *
 * <h2>Out-of-order tolerance</h2>
 * Current policy: {@code dependency_removed} for an unknown edge is a no-op, counted via
 * {@link #droppedRemovalsForUnknownEdges()}. {@code dependency_observed} always inserts
 * or updates. Timestamps are <i>not</i> compared to reject stale events. See the
 * "REVISIT" note in {@code CLAUDE.md} — this is the spec-minimum policy and may need to
 * be tightened to last-write-wins with persisted tombstones for absolute correctness.
 */
public class ServiceGraph {

    private final Map<String, ServiceNode> nodes = new HashMap<>();
    /**
     * Tombstones for last-write-wins ordering: {@code tombstones[(s,t)] = removedTs}
     * means the most recent {@code dependency_removed} for {@code s→t} was at
     * {@code removedTs}. An incoming {@code dependency_observed} with a strictly
     * older event-ts is rejected as stale.
     *
     * <p>In-memory only for the take-home. Lost on restart — a future production
     * iteration would persist these in a {@code tombstones(source, target, removed_ts)}
     * table so post-restart stale events are also rejected.
     */
    private final Map<EdgeKey, Instant> tombstones = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final int maxSamplesPerEdge;
    private final Duration sampleAgeCap;
    private final Clock clock;

    private long droppedRemovalsForUnknownEdges = 0L;
    private long droppedStaleObservations = 0L;
    private long droppedStaleRemovals = 0L;

    public ServiceGraph(int maxSamplesPerEdge, Duration sampleAgeCap, Clock clock) {
        if (maxSamplesPerEdge <= 0) {
            throw new IllegalArgumentException("maxSamplesPerEdge must be > 0");
        }
        if (sampleAgeCap == null || sampleAgeCap.isNegative() || sampleAgeCap.isZero()) {
            throw new IllegalArgumentException("sampleAgeCap must be a positive duration");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.maxSamplesPerEdge = maxSamplesPerEdge;
        this.sampleAgeCap = sampleAgeCap;
        this.clock = clock;
    }

    /** The retention window for in-memory samples. The {@code health()} window may not exceed it. */
    public Duration sampleAgeCap() {
        return sampleAgeCap;
    }

    // ---------------------------------------------------------------------------------
    // Mutations — every method here acquires the write lock.
    // ---------------------------------------------------------------------------------

    /**
     * Apply a {@code dependency_observed} event with last-write-wins semantics.
     *
     * <p>Rejected as stale (returns {@code null}) only when a tombstone exists for
     * {@code (source, target)} with {@code removedTs > event.ts} — i.e., the edge was
     * removed at a strictly later event time, so this observation is from before the
     * removal and shouldn't resurrect the edge.
     *
     * <p><b>Why no {@code lastObservedTs} check on observed events.</b> Multiple
     * consumers can pull two same-edge observations in any order; if we rejected the
     * one with the smaller {@code ts} as "stale", we'd silently drop a legitimate
     * sample whenever scheduling reorders adjacent events. The rolling average is
     * order-independent (just the mean), so accepting both produces the correct
     * final state regardless of who wins the write lock first. The {@code Edge}
     * itself still tracks {@code lastObservedTs = max(seen)}, used by
     * {@link #applyDependencyRemoved} to reject stale removals.
     *
     * @return rolling stats after the apply, or {@code null} if the event was rejected
     *         as stale and nothing changed.
     */
    public EdgeStats applyDependencyObserved(String source, String target,
                                             Instant ts, int latencyMs, Status status) {
        lock.writeLock().lock();
        try {
            EdgeKey key = new EdgeKey(source, target);
            Instant tombstone = tombstones.get(key);
            if (tombstone != null && tombstone.isAfter(ts)) {
                droppedStaleObservations++;
                return null;
            }
            ServiceNode src = ensureNode(source);
            ServiceNode tgt = ensureNode(target);
            Edge edge = src.outgoingTo(target);
            if (edge == null) {
                edge = new Edge(source, target, maxSamplesPerEdge, sampleAgeCap, clock);
                src.addOutgoing(edge);
                tgt.addIncoming(source);
            }
            edge.recordObservation(ts, latencyMs, status);
            return new EdgeStats(edge.rollingAvgLatencyMs(), edge.sampleCount());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Apply a {@code dependency_removed} event at event time {@code ts}, with last-write
     * -wins semantics:
     *
     * <ul>
     *   <li>If the edge currently exists and its {@code lastObservedTs} is strictly
     *       newer than {@code ts}, the removal is stale — we keep the edge.</li>
     *   <li>Otherwise: remove the edge (if present) and stamp a tombstone at
     *       {@code ts}. Tombstones are kept on the highest seen ts.</li>
     * </ul>
     *
     * <p>Removing an unknown edge still updates the tombstone — a future stale
     * observation can then be rejected. The {@code droppedRemovalsForUnknownEdges}
     * counter still increments for observability.
     */
    public void applyDependencyRemoved(String source, String target, Instant ts) {
        lock.writeLock().lock();
        try {
            EdgeKey key = new EdgeKey(source, target);
            ServiceNode src = nodes.get(source);
            Edge edge = src != null ? src.outgoingTo(target) : null;

            if (edge != null && edge.lastObservedTs() != null
                    && edge.lastObservedTs().isAfter(ts)) {
                droppedStaleRemovals++;
                return;
            }

            // Update tombstone (newer-wins).
            Instant existing = tombstones.get(key);
            if (existing == null || ts.isAfter(existing)) {
                tombstones.put(key, ts);
            }

            if (edge == null) {
                droppedRemovalsForUnknownEdges++;
                return;
            }
            src.removeOutgoing(target);
            ServiceNode tgt = nodes.get(target);
            if (tgt != null) {
                tgt.removeIncoming(source);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Apply a {@code service_metadata} event. Creates the node if absent. {@code null}
     * fields leave the corresponding existing value unchanged.
     */
    public void applyServiceMetadata(String id, String team, String tier, String region) {
        lock.writeLock().lock();
        try {
            ensureNode(id).setMetadata(team, tier, region);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Apply a {@code heartbeat} event. Creates the node if absent. */
    public void applyHeartbeat(String id, Instant ts) {
        lock.writeLock().lock();
        try {
            ensureNode(id).recordHeartbeat(ts);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Visible for the persistence layer's boot rehydration; not part of the event API. */
    public ServiceNode upsertNodeForRestore(String id) {
        lock.writeLock().lock();
        try {
            return ensureNode(id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Visible for the persistence layer's boot rehydration; reconstructs an edge from a row
     * in {@code edges}. Sample deque is populated separately via
     * {@link #restoreEdgeSample(String, String, com.groupon.sda.domain.graph.Sample)}.
     *
     * @param lastObservedTs may be {@code null} for legacy rows persisted before LWW landed
     */
    public Edge upsertEdgeForRestore(String source, String target,
                                     double rollingAvgLatencyMs, long sampleCount,
                                     Instant lastObservedTs) {
        lock.writeLock().lock();
        try {
            ServiceNode src = ensureNode(source);
            ServiceNode tgt = ensureNode(target);
            Edge edge = src.outgoingTo(target);
            if (edge == null) {
                edge = new Edge(source, target, maxSamplesPerEdge, sampleAgeCap, clock);
                src.addOutgoing(edge);
                tgt.addIncoming(source);
            }
            edge.restoreRollingStats(rollingAvgLatencyMs, sampleCount, lastObservedTs);
            return edge;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void restoreEdgeSample(String source, String target,
                                  com.groupon.sda.domain.graph.Sample sample) {
        lock.writeLock().lock();
        try {
            ServiceNode src = nodes.get(source);
            if (src == null) return;
            Edge edge = src.outgoingTo(target);
            if (edge == null) return;
            edge.restoreSample(sample);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private ServiceNode ensureNode(String id) {
        return nodes.computeIfAbsent(id, ServiceNode::new);
    }

    // ---------------------------------------------------------------------------------
    // Reads — every method here acquires the read lock.
    // ---------------------------------------------------------------------------------

    public boolean hasNode(String id) {
        lock.readLock().lock();
        try {
            return nodes.containsKey(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int nodeCount() {
        lock.readLock().lock();
        try {
            return nodes.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int edgeCount() {
        lock.readLock().lock();
        try {
            int count = 0;
            for (ServiceNode n : nodes.values()) {
                count += n.outgoingView().size();
            }
            return count;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Run a function under the graph's read lock with direct access to the node map.
     * Used by graph algorithms that want to traverse without copying. The function
     * <b>must not</b> mutate the graph and <b>must not</b> hold a reference to the map
     * after returning.
     */
    public <T> T withReadLock(Function<Map<String, ServiceNode>, T> fn) {
        lock.readLock().lock();
        try {
            return fn.apply(nodes);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Take a structural snapshot — node ids, outgoing adjacency with edge weights — under
     * the read lock, then return it for use outside any lock. Use this for long-running
     * algorithms (e.g., betweenness centrality) where holding the read lock for the
     * algorithm's full runtime would starve writers.
     *
     * <p>Edge weights in the snapshot are the rolling average latencies at the moment the
     * snapshot was taken. Sample-window data is intentionally excluded; if you need
     * health stats, query through {@link #computeHealth} instead.
     */
    public Snapshot structuralSnapshot() {
        lock.readLock().lock();
        try {
            Map<String, Map<String, Double>> outgoing = new HashMap<>(nodes.size() * 2);
            Map<String, Set<String>> incoming = new HashMap<>(nodes.size() * 2);
            for (ServiceNode n : nodes.values()) {
                Map<String, Edge> out = n.outgoingView();
                Map<String, Double> outCopy = new HashMap<>(out.size() * 2);
                for (Edge e : out.values()) {
                    outCopy.put(e.target(), e.rollingAvgLatencyMs());
                }
                outgoing.put(n.id(), Collections.unmodifiableMap(outCopy));
                incoming.put(n.id(), Collections.unmodifiableSet(new HashSet<>(n.incomingView())));
            }
            return new Snapshot(
                    Collections.unmodifiableMap(outgoing),
                    Collections.unmodifiableMap(incoming));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Compute health stats for the given service over {@code window} ending at {@code now}.
     * Returns {@code null} if the service is unknown or has no incident samples in window.
     *
     * <p>Aggregates over <b>both directions</b>: outgoing edges (calls this service made)
     * and incoming edges (calls made to this service). Spec wording is "edges incident to
     * the service" — incident means undirected adjacency.
     */
    public Edge.HealthSnapshot computeHealth(String serviceId, Instant now, Duration window) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be a positive duration");
        }
        if (window.compareTo(sampleAgeCap) > 0) {
            // Spec calls for an honest answer or an honest failure. Silently truncating
            // to the configured retention would let callers think they got a 10-min view
            // when they got 5. Reject so the API layer can return a 400.
            throw new IllegalArgumentException(
                    "requested window " + window + " exceeds configured retention "
                            + sampleAgeCap + " (sda.health.window-seconds)");
        }
        lock.readLock().lock();
        try {
            ServiceNode node = nodes.get(serviceId);
            if (node == null) return null;

            Instant cutoff = now.minus(window);
            int totalSamples = 0;
            int totalErrors = 0;
            int[] latencies = new int[256];
            int latIdx = 0;

            // Outgoing edges — iterate samples directly so the service-level p95 is
            // exact (computed over the full union of in-window samples) rather than an
            // average of per-edge p95s.
            for (Edge e : node.outgoingView().values()) {
                for (Sample s : e.recentSamplesView()) {
                    if (s.ts().isBefore(cutoff)) continue;
                    if (latIdx >= latencies.length) {
                        latencies = java.util.Arrays.copyOf(latencies, latencies.length * 2);
                    }
                    latencies[latIdx++] = s.latencyMs();
                    if (s.status() != Status.ok) totalErrors++;
                    totalSamples++;
                }
            }
            // Incoming edges. Skip the self-loop A->A: it was already accumulated via
            // node.outgoingView() above; visiting it again would double-count its samples.
            for (String sourceId : node.incomingView()) {
                if (sourceId.equals(serviceId)) continue;
                ServiceNode src = nodes.get(sourceId);
                if (src == null) continue;
                Edge e = src.outgoingTo(serviceId);
                if (e == null) continue;
                for (Sample s : e.recentSamplesView()) {
                    if (s.ts().isBefore(cutoff)) continue;
                    if (latIdx >= latencies.length) {
                        latencies = java.util.Arrays.copyOf(latencies, latencies.length * 2);
                    }
                    latencies[latIdx++] = s.latencyMs();
                    if (s.status() != Status.ok) totalErrors++;
                    totalSamples++;
                }
            }

            if (totalSamples == 0) return null;
            int[] sorted = java.util.Arrays.copyOf(latencies, latIdx);
            java.util.Arrays.sort(sorted);
            int p95Index = Math.min(latIdx - 1, (int) Math.ceil(0.95 * latIdx) - 1);
            if (p95Index < 0) p95Index = 0;
            int p95 = sorted[p95Index];

            double errorRate = (double) totalErrors / totalSamples;
            return new Edge.HealthSnapshot(totalSamples, errorRate, p95);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Counter for ops/observability — number of removal events targeting unknown edges. */
    public long droppedRemovalsForUnknownEdges() {
        lock.readLock().lock();
        try {
            return droppedRemovalsForUnknownEdges;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** LWW counter — observations rejected as stale (older than tombstone or lastObservedTs). */
    public long droppedStaleObservations() {
        lock.readLock().lock();
        try {
            return droppedStaleObservations;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** LWW counter — removals rejected as stale (edge has a newer observation). */
    public long droppedStaleRemovals() {
        lock.readLock().lock();
        try {
            return droppedStaleRemovals;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Composite key for tombstones and other edge-keyed maps. */
    public record EdgeKey(String source, String target) {
    }

    /**
     * Immutable structural view of the graph: {@code outgoing} maps node id → (target id →
     * rolling-avg latency); {@code incoming} maps node id → set of source ids. Safe to
     * traverse outside any lock. Use for algorithms that must not block ingest.
     */
    public record Snapshot(Map<String, Map<String, Double>> outgoing,
                           Map<String, Set<String>> incoming) {
    }
}
