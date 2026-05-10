package com.groupon.sda.graph;

/**
 * In-memory directed graph of services and dependencies.
 *
 * Spec constraints (do not violate when implementing):
 *   - Thread-safe: concurrent reads (queries) and writes (event application) must not tear or deadlock.
 *   - Idempotent edge add/remove given duplicate or out-of-order events.
 *   - Fast adjacency lookups for reachable / dependents queries.
 *   - Per-edge rolling latency stats for shortest_path and health queries.
 *   - No graph DBs / libraries (Neo4j, Memgraph, NetworkX, JGraphT) — build from standard data structures.
 */
public class ServiceGraph {
    // TODO: nodes, forward + reverse adjacency lists, rolling stats, locking strategy.
}
