# Service Dependency Analyzer — Design Report

## Architecture

**Language and runtime.** Java 21 + Spring Boot 3.3.x. Producers and consumers run on virtual threads (`Thread.ofVirtual()`), so blocking on the queue lock or the SQLite write lock costs nothing in scheduling. Spring MVC for HTTP, springdoc for the OpenAPI/Swagger surface.

**Queue model.** Two layers. A bounded MPMC `EventQueue` interface is implemented by `ArrayBlockingQueueAdapter`, which wraps `java.util.concurrent.ArrayBlockingQueue` (a `ReentrantLock` + two `Condition`s + a circular array — the textbook "mutex-protected ring buffer" the spec invites) and adds a `closed` flag, a drain protocol where `take()` returns `null` once closed-and-empty, and a metrics snapshot. On top sits `PartitionedEventQueue`, which holds one `EventQueue` per consumer thread. Events are routed by hashing `source + "→" + target` for edge events and the service id for service events. The result is **per-edge serial ordering**: every event for `(source, target)` is processed by the same consumer thread in arrival order, while different edges still process in parallel across consumers. Backpressure default is **BLOCK** — a producer trying to publish to a full partition parks on `Condition.await` until a consumer makes room. The HTTP ingest path uses a 1-second timed `offer` so a full partition surfaces as `503 Service Unavailable` instead of pinning a Tomcat thread.

**Graph representation.** Adjacency list with both directions maintained. Each `ServiceNode` holds metadata, last heartbeat, an outgoing `Map<String, Edge>`, and an incoming `Set<String>` of source ids. `Edge` owns the topology and rolling stats — source, target, `rollingAvgLatencyMs`, `sampleCount`, a bounded `ArrayDeque<Sample>` for the health window, and a `lastObservedTs` for last-write-wins ordering. Reverse traversals (`dependents`) walk `incoming` ids and look up the source node — one extra map hop per step in exchange for `O(1)` reverse-edge enumeration.

**Concurrency strategy.** A single `ReentrantReadWriteLock` at the graph level. All mutations under `writeLock`; all reads under `readLock`. The internal node, edge, and sample containers are plain (non-concurrent) collections — the lock provides happens-before for every observation. For the one query whose worst case is more than microseconds (`critical_services` if we ever swap in betweenness), the algorithm takes a structural snapshot under the read lock and computes outside the lock; BFS, Dijkstra, and cycle detection are fast enough to run inline.

**Persistence.** SQLite at `./data/graph.db`, in WAL mode for read concurrency. The DB *is* the persisted state — there's no separate snapshot or event log. Schema: `services`, `edges` (with rolling stats and `last_observed_ts`), `edge_samples`, `processed_events`. On boot, `GraphRestoreRunner` reads the four tables back into the in-memory graph; ingestion resumes from there. Per event the consumer mutates in-memory under `writeLock` (returning post-update rolling stats), then a single transaction inserts the `processed_events` row and the data rows. Crash semantics are documented: a JVM death between memory and DB commit drops one event, accepted scope for the take-home given the spec forbids durable upstream queues.

## Criticality metric

`score(v) = inDegree(v) × outDegree(v)`. A service that is *both* heavily depended on (high in-degree) *and* depends on many others (high out-degree) sits on the most call paths; removing it severs the most pairs of services from each other.

This is a hybrid choice over Brandes' betweenness centrality. Trade-offs:

- **Cost** — `O(V)` here vs. `O(V·E)` for Brandes. At 10 000 services and 100 000 edges, betweenness is ~1 billion ops and runs in seconds; the hybrid runs in microseconds, fast enough to call inline under the read lock instead of needing snapshot + lock-release.
- **Accuracy** — betweenness is the textbook "lies on the most shortest paths between all pairs" answer. The hybrid is a coarse approximation that doesn't account for global path structure: hub services with many neighbours on each side score correctly; chokepoint services with few direct neighbours but high transitive traffic score lower than they should.
- **Determinism** — ties break by service id (lex), so the response is stable across calls.

Brandes is the natural next step. The metric is interface-isolated to one class (`Criticality`), so swapping it in is local.

## Trade-offs

**Simplicity over scale, deliberately:**

- **SQLite over a sharded write store.** SQLite serializes writes via its file lock, which is the bottleneck somewhere around 10–50 k inserts/sec. We sized everything (HikariCP pool, partitioned queue) to that ceiling. A sharded Postgres / Cassandra / FoundationDB would lift it 10–100×, but the spec's scale (50–200 k events total) doesn't need that.
- **`ArrayBlockingQueue` over a lock-free MPMC ring (Disruptor / JCTools).** Single-lock contention tops out around the millions-of-ops/sec mark, well above what SQLite can absorb anyway. The `EventQueue` interface keeps the swap to a lock-free implementation a one-line change if it ever matters.
- **Hybrid criticality over Brandes' betweenness.** Discussed above.
- **Bounded `ArrayDeque<Sample>` per edge instead of a streaming sketch (T-digest, Greenwald-Khanna).** Exact, easy to reason about, and at this scale never exceeds tens of MB even in pathological hot-edge cases.
- **Elementary cycles via "DFS from each vertex with a lex-min start" instead of full Johnson's.** Same correctness on directed graphs (each elementary cycle has a unique lex-smallest entry point); fewer moving parts.
- **In-memory tombstones for the LWW out-of-order policy** instead of a `tombstones` table. They survive the JVM but not a restart. `last_observed_ts` *is* persisted on `edges`, so surviving edges keep their staleness frontier — the gap is only on edges that were removed and need to reject a post-restart stale observation. Acceptable given the assessment doesn't exercise that window.

**Scale over simplicity, in two places:**

- **Partitioning consumers by edge.** A single shared queue with N consumers would have been simpler, but we'd have given up per-edge ordering and watched fixtures flake under reorder. The N-partition design preserves cross-edge concurrency without sacrificing correctness.
- **Two-layer dedup (atomic in-memory `tryClaim` + `processed_events` PK constraint).** A pure DB check on every event would have been correct but added a SELECT-per-event of latency. The cache short-circuits the common path; the PK is the durable second line of defence.

## What I'd build next

- **Request-trace correlation.** Today an event carries `(source, target, latency, status)` but no trace context. Adding `trace_id` (and optionally `parent_span_id`) to `dependency_observed` lets a single end-user request be followed across the dependency graph: "for this trace, which path did the call take, where did the latency live, where did it fail?" The graph already knows the topology; correlating to traces upgrades the on-call answer from "the blast radius of payments-service" to "this specific user got their 504 because their request hit the slow path through cache-redis." Cheapest implementation: a `traces(trace_id, source, target, ts, span_ms, status)` table keyed on `trace_id`, plus a new `GET /api/v1/graph/trace/{trace_id}` query that returns the ordered span list.
- **Observability.** Micrometer is on the classpath but we don't yet emit our own meters. Queue depth per partition, events processed per type, dedup-via-cache vs. dedup-via-DB rates, query-latency histograms, `dropped_events` / `rejected_events` / `droppedStaleObservations` / `droppedStaleRemovals` counters — all should land in Prometheus/Grafana with sensible default dashboards.
- **Anomaly detection.** A small EWMA per edge for latency *and* error rate would let us flag "this edge's behaviour has shifted significantly from its recent baseline" cheaply, surfacing as a new `GET /api/v1/graph/anomalies` endpoint or as a streamable signal.

## Proud of / didn't go to plan

**Proud of.**

- The two-layer dedup. `tryClaim` on a `ConcurrentHashMap.newKeySet()` is atomic by JDK contract, so within one JVM only one consumer per `event_id` ever proceeds to graph mutation. The `processed_events` PK constraint catches duplicates that survive a restart. Together they meet the spec's idempotency bar without locking the whole graph for a DB roundtrip.
- Splitting LWW into "tombstone check on observed" and "lastObservedTs check on removed". The first iteration applied the lastObservedTs check to *both* sides, which made the ooo-02 fixture pass but flaked the health/01 fixture (legitimate observations rejected as stale under multi-consumer reorder). Realising that the rolling average is order-independent — so multiple observations can land in any order without affecting the final state — and dropping that one check was the cleanest correctness fix of the project.
- The fixture-driven test harness. Hand-crafted JSON files for every spec invariant (topology shapes, idempotency, out-of-order, restart, concurrency, negative inputs), each documented in a README with the expected query answers, and exercised by both an in-process JUnit harness and a black-box bootJar-over-HTTP script. When something regresses you know exactly which invariant.

**Didn't go as hoped.**

- Getting LWW right took three iterations: simple no-op → over-aggressive double check → tombstone-only on observed. Should have written the fixture for ooo-02 *before* designing the policy; the third iteration is what we'd have arrived at directly.
- The HikariCP pool sizing started life as a magic `7` in `application.yml`. It took a code review round to notice that bumping `consumer-count` would silently starve consumers on connection acquisition. Now sized programmatically in `IngestConfig.dataSource(...)` from `consumer-count + 5`, but the lesson is to tie related config knobs together at construction, not in YAML.
- The ServiceMetadata event carries a free-form `Map<String, String>` while the schema has columns for `team`, `tier`, `region`. The event-to-row extraction lives in the consumer. Cleaner would have been to either tighten the event schema or generalise the storage. As-is, a future "store extra metadata" requirement will need a schema migration.
