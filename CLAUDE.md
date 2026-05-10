# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A backend take-home: ingest a stream of service-dependency events, maintain an in-memory directed graph, and expose analytical queries (blast radius, reverse dependencies, shortest path, critical services, cycles, health). Full spec at `~/Downloads/backend-take-home-assessment.md`.

The repo is currently a **boilerplate**. Most modules are placeholders with TODO comments. Build them out as the assessment requires.

## Build / run / test

```bash
./gradlew build              # compile + test
./gradlew bootRun            # start the API on :8080
./gradlew test               # run all tests
./gradlew test --tests FQCN  # run a single test class (e.g. com.groupon.sda.queue.EventQueueTest)
./gradlew bootJar            # build runnable jar
```

The Gradle wrapper is committed and uses Gradle 8.10. Java 21 is required; the foojay toolchain resolver (configured in `settings.gradle.kts`) will auto-provision a matching JDK on first build if none is found locally.

## Architecture decisions already made

- **Language/runtime:** Java 21 + Spring Boot 3.3.x.
- **Build:** Gradle (Kotlin DSL).
- **API:** HTTP/REST via Spring MVC. OpenAPI/Swagger UI via springdoc.
- **Persistence:** **SQLite** (single file at `./data/graph.db`). The DB *is* the persisted state — no separate snapshot or event log. Schema lives in `src/main/resources/schema.sql` and is applied at boot via Spring's `spring.sql.init`. No event replay on boot — repositories `SELECT` rows back into the in-memory graph and ingestion resumes from there.
- **Concurrency:** custom in-memory bounded queue (no Kafka/Redis/etc. — spec forbids), N producers + M consumers configurable via `sda.ingest.*`. Producer/consumer threads are **virtual threads** (`Thread.ofVirtual()`) — they're cheap, blocking on the queue lock or the SQLite write lock doesn't pin a platform thread.
- **Graph:** custom in-memory adjacency structure (no graph DBs / libraries — spec forbids).

## Queue design

The `queue.EventQueue` interface is a thin abstraction over a bounded MPMC pipe. The default implementation, `ArrayBlockingQueueAdapter`, wraps `java.util.concurrent.ArrayBlockingQueue` and adds:

- A `closed` flag (`volatile`) plus `close()` semantics — the JDK queue has no native "closed" state.
- A drain protocol: once `close()` is called, `put`/`offer` reject new items with `QueueClosedException`; `take`/`poll` keep returning items until the buffer is empty, then return `null` to signal "closed and drained" so consumers can exit cleanly.
- `QueueMetrics` snapshot (depth, totalEnqueued, totalDequeued, totalDropped, putBlockedNanos) for `/actuator/metrics`.

**Backpressure policy: BLOCK (default).** Producers call `put(event)` which blocks on the underlying lock when the buffer is full. The producer thread parks via `Condition.await` until a consumer makes room. Throughput self-throttles to consumer speed; producers never silently drop. An optional `sda.ingest.put-timeout-ms` flips `put` to a timed `offer`, dropping with a logged WARN and a `dropped_events` counter — off by default because losing a `dependency_removed` corrupts the graph.

**The queue is dumb on purpose.** It does not know about events beyond the type parameter, does not dedup, does not persist. Idempotency and out-of-order tolerance live downstream (consumer + graph layer).

**Why `ArrayBlockingQueue` and not a hand-rolled ring buffer?** Same internals (`ReentrantLock` + 2 `Condition`s + circular array), well-tested. The `EventQueue` interface keeps the swap to a lock-free MPMC queue (e.g., a hand-built CAS-based ring) a one-line change if contention ever becomes the bottleneck — which it won't before the SQLite single-writer becomes the bottleneck first.

## Dedup design

Idempotency is handled at the **consumer**, not the queue. Two cooperating layers:

1. **`SeenEventsCache`** (in-memory, pluggable) — fast pre-check. `contains(eventId)` may return false positives in probabilistic implementations but never false negatives. Default impl is `InMemorySetCache` backed by `ConcurrentHashMap.newKeySet()` (exact). Production swap point: `BloomFilterCache` (rolling, TTL'd) when the in-memory exact set becomes a memory bottleneck.
2. **`processed_events` table** (durable, authoritative) — source of truth across restarts and on bloom-filter false positives. Insertion uses `INSERT ... ON CONFLICT(event_id) DO NOTHING`; the PK constraint is the second line of defence.

Consumer flow per event:

```
event = queue.take()
if event == null: return                     // shutdown drained

if not cache.tryClaim(event.id):
    if processedEventsRepo.exists(event.id): // confirm against authoritative store
        metrics.duplicatesViaCache++
        continue
    // BF false positive — fall through (unreachable for the exact-set impl)

if processedEventsRepo.exists(event.id):     // restart case: id was in DB before this JVM
    metrics.duplicatesViaDb++
    continue

stats = graph.apply(event)                   // in-memory mutation under writeLock,
                                             // returns post-update rolling stats

tx.begin()
processedEventsRepo.insertIfAbsent(event.id) // belt-and-braces against the gate
graphMutationRepo.apply(event, stats)        // services/edges/edge_samples row writes
tx.commit()
```

**Order rationale.** In-memory mutation happens *first* (under `writeLock`) so the
post-mutation rolling stats are available to the persistence transaction in a single
round-trip. Concurrent observations on the same edge enter the write lock one at a
time; the second sees the first's update and produces a larger `sample_count`.
Their DB transactions can commit in any order — `EdgeRepository.upsert`'s
`ON CONFLICT` clause keeps the row consistent with the higher-`sample_count` write.

**Crash semantics.** If the JVM dies between the in-memory mutation and the DB
commit, the `processed_events` row is missing. On the next boot, `GraphRestoreRunner`
rebuilds the in-memory graph from `services` / `edges` / `edge_samples`; the lost
event is *not* re-applied (the synthetic generator doesn't re-deliver, and our
in-process queue isn't durable). This is accepted scope for the take-home — at-least-once
delivery would require a durable upstream (Kafka) which the spec explicitly forbids.

## Graph design

### Representation

Adjacency list with both directions maintained:

```
ServiceNode {
    String id, team, tier, region
    Instant lastHeartbeat
    Map<String, Edge> outgoing   // target id -> Edge owning topology + stats
    Set<String> incoming         // source ids only — edge object lives on the source
}
Edge {
    String source, target
    double rollingAvgLatencyMs
    long sampleCount
    ArrayDeque<Sample> recentSamples   // bounded by size + age
}
Sample(Instant ts, int latencyMs, Status status)
```

The graph itself is a `Map<String, ServiceNode> nodes` plus the lock. Reverse traversals (`dependents`) walk `incoming` ids and look up the source node — one extra map hop per step, in exchange for `O(1)` reverse-edge enumeration.

### Concurrency

A single `ReentrantReadWriteLock` at the graph level. All mutations under `writeLock`; most queries under `readLock`. Java's `ConcurrentHashMap` is **not** used inside — the lock provides happens-before for every read, and mixing both costs more than it gives.

For long-running queries (notably `critical_services` doing betweenness centrality, which is `O(V·E)`), the algorithm takes a structural snapshot under `readLock`, releases, then computes outside the lock. BFS / Dijkstra / cycle detection are fast enough to run inline under `readLock`.

### Out-of-order tolerance — current policy: **simple no-op**

- `dependency_removed` for an edge that doesn't exist → no-op + counter increment, no error.
- `dependency_observed` always inserts/updates the edge with the event's stats.
- We do **not** track per-edge tombstones or compare timestamps to reject stale events.

> **REVISIT for absolute correctness.** Once the synthetic generator and tests land, validate that this policy passes the spec's correctness bar at test scale. If it doesn't, the next step is last-write-wins by timestamp with persisted tombstones (`edges.last_observed_ts`, `tombstones(source, target, removed_ts)`). See task #13.

### Health window storage

Two-layer storage for `health(service, window)`:

1. **In-memory bounded deque per `Edge`** — feeds the live query path. Bounded by both size (`MAX_SAMPLES`, default 1024) and age (`sda.health.window-seconds`). Self-evicting on the writer path (every `recordObservation`); reads filter in-place by timestamp without mutating, so multiple readers can hold the graph's read lock concurrently without tearing.
2. **`edge_samples` table** — durable backing. Every `dependency_observed` writes a row; on boot, recent rows are read back into the deques so queries return correct answers before any new events arrive.

`p95` is computed by sorting the in-window slice on each query — bounded N means it's microseconds.

### Persistence flow

Reaffirms the rule already set in the persistence section: every event apply is **DB write inside a transaction → in-memory mutation under `writeLock`**. In-memory state is always derivable from durable state, so a crash between commit and in-memory mutation converges on restart.

## Module layout

All packages live under `com.groupon.sda`:

| Package | Role |
|---|---|
| `api` | REST controllers (`GraphQueryController`, `EventIngestController`) and DTOs |
| `domain.event` | Sealed `Event` hierarchy: `DependencyObservedEvent`, `DependencyRemovedEvent`, `ServiceMetadataEvent`, `HeartbeatEvent` |
| `domain.graph` | Node / edge value types |
| `graph` | In-memory `ServiceGraph` |
| `graph.algorithms` | BFS/DFS, Dijkstra, cycle detection, criticality |
| `queue` | Custom `EventQueue` interface + implementations |
| `ingest` | Producer and consumer runnables, lifecycle wiring |
| `persistence` | SQLite repositories (JdbcTemplate-based) |
| `events.generator` | Synthetic event generator for the dataset the spec asks for |
| `helpers` | Cross-cutting utilities |
| `config` | Spring config, OpenAPI bean |

## Spec constraints (do not violate when implementing)

- **No off-the-shelf queues** (Kafka, RabbitMQ, NATS, Redis Streams, SQS) — build the queue from primitives.
- **No graph DBs / libraries** (Neo4j, Memgraph, NetworkX, JGraphT) — build the graph and algorithms from standard data structures.
- **Idempotent ingest** — duplicate `event_id`s must not corrupt the graph (see `processed_events` table).
- **Out-of-order tolerant** — a `dependency_removed` for an edge that hasn't arrived yet must not crash or leave inconsistent state.
- **Backpressure must be deliberate** — block producers or shed with intent; never silently drop. Document the choice.
- **Graceful shutdown** — on SIGTERM, drain in-flight events and close the DB. Spring's `server.shutdown: graceful` is set; consumers must respect the shutdown signal.
- **Thread-safe graph** — concurrent reads (queries) and writes (ingest) must not tear or deadlock.

## Persistence model

Schema in `src/main/resources/schema.sql`:

| Table | Purpose |
|---|---|
| `services(id, team, tier, region, last_heartbeat_ts)` | Nodes + metadata + heartbeat |
| `edges(source, target, rolling_avg_latency_ms, sample_count)` | Currently live edges with rolling stats |
| `edge_samples(source, target, ts, latency_ms, status)` | Recent samples for `health()` window queries |
| `processed_events(event_id, processed_ts)` | Idempotency set |

Boot sequence:

1. Spring opens the SQLite file at `./data/graph.db` and runs `schema.sql` (CREATE TABLE IF NOT EXISTS).
2. `SqlitePragmaInitializer` (Order 0) flips the file to WAL mode and sets `synchronous=NORMAL` for the boot connection. WAL mode is persistent at the file level; subsequent connections inherit it. The Hikari `connection-init-sql` re-applies `synchronous=NORMAL` per-connection because that pragma is connection-scoped.
3. `GraphRestoreRunner` (Order 10) reads `services`, `edges`, and recent `edge_samples` (within `sda.health.window-seconds`) and replays them into the in-memory `ServiceGraph` via the `upsertNodeForRestore` / `upsertEdgeForRestore` / `restoreEdgeSample` hooks.
4. After that, every event apply commits its row updates inside a JDBC transaction — no event-log replay needed because the DB is always current.

`PersistenceMaintenance` runs on `@Scheduled(fixedDelayString = "PT1M")` and prunes `edge_samples` older than the retention window so the table doesn't grow unbounded across long runs.

SQLite serializes writes via its file lock; the Hikari pool is capped at `consumer-count + 5` (default 7) so we don't allocate more connections than the single-writer DB can usefully serve. WAL mode ensures readers (API request threads) don't block writers (consumers) and vice versa.

## Critical-services metric

`score(v) = inDegree(v) × outDegree(v)`. A service that is both heavily depended upon (high in-degree) and heavily depends on others (high out-degree) sits on the most call paths; removing it severs the most pairs of services from each other.

This is a hybrid take-home choice over Brandes' betweenness centrality. Trade-offs:

- **Cost**: O(V) here vs. O(V·E) for Brandes — at 10k services / 100k edges, betweenness is ~1B operations and runs in seconds; the hybrid runs in microseconds.
- **Accuracy**: betweenness is the textbook answer to "which nodes lie on the most shortest paths between all pairs." This hybrid is a coarse approximation that doesn't account for global path structure. Hub services with many neighbours on each side score correctly; chokepoint services with few direct neighbours but high traffic flow score lower than they "should."
- **Determinism**: ties are broken by service id (lexicographic) for stable output.

The report calls out betweenness as the natural next step.

## API surface

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/events` | Publish one event |
| POST | `/api/v1/events/batch` | Publish many events |
| GET  | `/api/v1/graph/reachable/{service}` | Blast radius |
| GET  | `/api/v1/graph/dependents/{service}` | Reverse reachability |
| GET  | `/api/v1/graph/shortest-path?source=&target=` | Lowest-latency path |
| GET  | `/api/v1/graph/critical-services?k=` | Top-k by criticality metric |
| GET  | `/api/v1/graph/cycles` | All current cycles |
| GET  | `/api/v1/graph/health/{service}?windowSeconds=` | Error rate + p95 latency over window |
| GET  | `/swagger-ui.html` | Interactive docs |

## Configuration knobs

In `application.yml` under `sda.*`:

- `ingest.producer-count`, `ingest.consumer-count`, `ingest.queue-capacity`
- `ingest.put-timeout-ms` *(optional, off by default)* — when set, switches the producer's `put` to a timed `offer`. On timeout the event is dropped with a structured WARN log and the `dropped_events` counter increments. Use only when you'd rather lose events than block; default behaviour is to block forever.
- `ingest.dedup-cache-capacity` — sizing hint for `SeenEventsCache`. The `InMemorySetCache` ignores it; future `BloomFilterCache` will use it.
- `health.window-seconds`, `health.max-samples-per-edge`
- `events.generator.enabled` — master switch for the synthetic generator (default `true`)
- `events.generator.service-count`, `events.generator.event-count`, `events.generator.hub-count`, `events.generator.cycles-to-inject`, `events.generator.error-rate`, `events.generator.duplicate-rate`, `events.generator.seed`

## Lifecycle

Startup order, controlled by Spring's lifecycle phases plus event-listener wiring:

1. Spring instantiates beans, runs `spring.sql.init` to apply `schema.sql`.
2. `ContextRefreshedEvent` fires → `SqlitePragmaInitializer` (Order 0) flips WAL → `GraphRestoreRunner` (Order 10) reads `services` / `edges` / recent `edge_samples` into the in-memory graph.
3. `SmartLifecycle.start()` runs in phase order: `ConsumerManager` (phase 500) spawns N virtual-thread consumers; then `ProducerManager` (phase 1000) spawns N virtual-thread producers and a watchdog that closes the queue when the synthetic generator exhausts.

Shutdown runs in reverse phase order: `ProducerManager.stop()` (phase 1000) closes the queue first (so any blocked `put` wakes with `QueueClosedException`) and joins producers; then `ConsumerManager.stop()` (phase 500) joins consumers as they drain the buffer and exit on `take() == null`. Spring's `server.shutdown: graceful` ensures HTTP requests in flight complete before the data-source closes.

## What's still placeholder

- Observability — structured logs are in; metrics (queue depth, events processed, query latency histograms) via Micrometer are an easy next step.
- Test coverage — idempotency + concurrent correctness + algorithm correctness on hand-crafted graphs are covered. Restart-consistency at end-to-end scale isn't yet automated; manual via "run, stop, restart, query".
