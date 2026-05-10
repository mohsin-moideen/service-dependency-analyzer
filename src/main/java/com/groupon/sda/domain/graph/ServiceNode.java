package com.groupon.sda.domain.graph;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A vertex in the dependency graph: one logical service plus its directed neighbours.
 *
 * <p><b>Concurrency contract:</b> this class is <i>not</i> internally synchronized. It is
 * always touched while the owning {@code ServiceGraph}'s read or write lock is held —
 * mutators ({@link #setMetadata}, {@link #recordHeartbeat}, {@link #addOutgoing}, etc.)
 * under the write lock; accessors under the read lock.
 *
 * <p>The {@link #outgoing} map owns its {@link Edge} objects. {@link #incoming} stores
 * <i>source ids only</i>; reverse traversals look up the actual edge on the source's
 * outgoing map. Storing edges in two places would only invite consistency bugs.
 */
public final class ServiceNode {

    private final String id;
    private String team;
    private String tier;
    private String region;
    private Instant lastHeartbeat;

    private final Map<String, Edge> outgoing = new HashMap<>();
    private final Set<String> incoming = new HashSet<>();

    public ServiceNode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String team() {
        return team;
    }

    public String tier() {
        return tier;
    }

    public String region() {
        return region;
    }

    public Instant lastHeartbeat() {
        return lastHeartbeat;
    }

    /** Replace any subset of metadata fields. {@code null} arguments leave the field unchanged. */
    public void setMetadata(String team, String tier, String region) {
        if (team != null) this.team = team;
        if (tier != null) this.tier = tier;
        if (region != null) this.region = region;
    }

    /** Newest-wins; older heartbeats are ignored. */
    public void recordHeartbeat(Instant ts) {
        if (lastHeartbeat == null || ts.isAfter(lastHeartbeat)) {
            this.lastHeartbeat = ts;
        }
    }

    /**
     * Returns the existing outgoing {@link Edge} to {@code target}, or {@code null} if none.
     * The graph layer is responsible for creating the {@link Edge} when needed.
     */
    public Edge outgoingTo(String target) {
        return outgoing.get(target);
    }

    public void addOutgoing(Edge edge) {
        outgoing.put(edge.target(), edge);
    }

    public Edge removeOutgoing(String target) {
        return outgoing.remove(target);
    }

    /** Read-only view; backing map is mutated only under the graph's write lock. */
    public Map<String, Edge> outgoingView() {
        return Collections.unmodifiableMap(outgoing);
    }

    public void addIncoming(String sourceId) {
        incoming.add(sourceId);
    }

    public void removeIncoming(String sourceId) {
        incoming.remove(sourceId);
    }

    /** Read-only view; backing set is mutated only under the graph's write lock. */
    public Set<String> incomingView() {
        return Collections.unmodifiableSet(incoming);
    }
}
