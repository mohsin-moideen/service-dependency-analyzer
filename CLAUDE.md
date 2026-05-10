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

Container path:

```bash
docker compose up -d         # builds + starts `sda` and the `sda-mcp` sidecar
docker compose ps            # `sda` should report healthy on :8080
docker compose down
```

The compose stack is two services: `sda` (the Spring Boot app, multi-stage `Dockerfile` at the repo root) and `sda-mcp` (the MCP sidecar — see `## MCP server` below). SQLite state lives on the `sda-data` named volume so it survives `down`.

## End-to-end fixture tests

Hand-crafted JSON fixtures live in `src/test/resources/fixtures/`, organised by the property each one targets:

| Subdir | Targets |
|---|---|
| `topology/` | Query correctness on known shapes — chain, diamond, fan-in/out hubs, self-loop, 2/3-cycle, overlapping cycles, pure DAG, disconnected, single-node, empty, plus tied shortest-paths and tied criticality scores for deterministic tiebreak validation |
| `idempotency/` | Exact, cross-producer, and conflicting-payload duplicates |
| `out-of-order/` | Stale removal before observation, observe-remove-observe round trip, metadata-before-edges, and the LWW pinning fixture (stale observation after a newer removal must be rejected) |
| `health/` | Hand-counted p95, mixed statuses, sample on the window boundary, all-out-of-window |
| `metadata/` | Incremental merge — three sequential metadata events each setting only one attribute, validates `COALESCE` semantics |
| `concurrency/` | The hot-edge fixture (50 distinct ids on the same edge) used by the concurrent writers/readers test |
| `restart/` | The rich mixed-stream (~35 events: hubs, planted cycle, removal, duplicate, late metadata) used by `RestartConsistencyTest` |
| `negative/` | Intentionally invalid wire-format inputs for the API error-path tests — malformed JSON, missing field, bad enum, unknown event type, bad timestamp |

Each fixture file is a JSON array shaped for `POST /api/v1/events/batch`. The README in `src/test/resources/fixtures/` spells out the expected query answers for every fixture and links each one to the test class that consumes it.

### Two harnesses

**1. `scripts/run_fixture_tests.sh`** — black-box, runs the bootJar and POSTs over HTTP:

```bash
./gradlew bootJar                                  # prerequisite
bash scripts/run_fixture_tests.sh                  # run every fixture
bash scripts/run_fixture_tests.sh topology         # filter (substring match on name or path)
bash scripts/run_fixture_tests.sh topology/02-diamond
bash scripts/run_fixture_tests.sh idempotency
```

For each matched fixture the script:

1. Wipes `/tmp/sda_test/data/`, boots the bootJar with `--sda.events.generator.enabled=false` and `--sda.health.window-seconds=86400` so the synthetic generator stays out of the way and the queries can use a wide window.
2. POSTs the fixture to `/api/v1/events/batch`.
3. Hits the relevant query endpoints and diffs the JSON against the README's expected outcomes.
4. Stops the server and frees port 8080 before the next fixture.

Pre-flight: free port 8080 first (`lsof -i :8080`); a leftover `bootRun` will block bind and silently route the test queries to the wrong server. The script tries to pick up the project's foojay-installed JDK 21 from `~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/...` — adjust the `JAVA` variable at the top of the script for a different install.

**2. JUnit-based fixture tests** — in-process, via `./gradlew test`. Two test-support classes glue fixtures to the rest of the system:

- `testsupport.FixtureLoader` — Jackson-backed loader that reads `fixtures/<path>` from the classpath and returns `List<Event>` (or raw bytes for negative fixtures that intentionally fail to deserialize).
- `testsupport.TestStack` — opens a full end-to-end stack (SQLite file, repos, `ServiceGraph`, `EventConsumer`) against an explicit DB path, and replays the same `services`/`edges`/`edge_samples` rebuild that `GraphRestoreRunner` runs on Spring boot. Closing and re-opening against the same path simulates a real restart cycle.

Fixture-driven JUnit tests:

| Test class | Spec invariant exercised |
|---|---|
| `ingest.consumer.EventConsumerIdempotencyTest` | Idempotency, including a 16-thread same-id race |
| `restart.RestartConsistencyTest` | "After a restart, queries must return the same answers as before" — ingests `restart/01-rich-mixed-stream.json`, snapshots all six query results, closes the stack, re-opens against the same SQLite file, re-queries, asserts every answer matches |
| `concurrency.HotEdgeConcurrencyTest` | Thread-safe graph: 8 writer threads each dispatch the full 50-event hot-edge fixture (every id contended by all 8) while 8 reader threads run `reachable`/`health` queries throughout. Asserts final `sample_count = 50`, `appliedCount = 50`, no torn reads, no deadlock |
| `queue.BackpressureTest` | "Producers must block or shed deliberately, not silently drop." Two paths: BLOCK (slow consumer, asserts 0 dropped + putBlockedNanos > 0) and SHED (full queue + timed offer, asserts the dropped counter increments) |
| `queue.GracefulShutdownTest` | Drain-after-close ordering, post-close `put`/`offer` rejection, parked-producer-during-close hand-off, consumer-loop-exits-cleanly |
| `api.ApiErrorPathTest` | `@WebMvcTest` slice for `GraphQueryController` — asserts 404 `service_not_found`, 400 `invalid_request`, structured `ApiError` body, and explicitly that error responses contain no stack-trace strings |
| `api.EventIngestApiErrorTest` | `@WebMvcTest` slice for `EventIngestController` — posts each `negative/*.json` fixture and asserts 400/503 with structured ApiError bodies; covers `QueueClosedException → 503` |

### Resolved fixture divergences

- ✅ `topology/05-self-loop` and `topology/06-2-cycle`: fixed by tracking `reached` (length-≥-1 nodes) separately from `discovered` (queue dedup) in `Reachability.bfs`, plus a special-case path build for the start-via-cycle case.
- ✅ `topology/08-overlapping-cycles`: replaced one-cycle-per-SCC with elementary-cycle enumeration in `Cycles` (DFS from each vertex with a lex-min constraint). Spec wording "all dependency cycles" is now satisfied literally.
- ✅ `out-of-order/02-observed-removed-observed`: implemented last-write-wins by event timestamp. `Edge.lastObservedTs` and an in-memory `tombstones: Map<EdgeKey, Instant>` on `ServiceGraph` reject stale observations and stale removals. The consumer marks rejected events as processed (so re-deliveries dedup) without writing data rows. `last_observed_ts` is persisted on the `edges` row so post-restart stale checks still work for surviving edges; tombstones for removed edges are intentionally in-memory only — a `tombstones` table is the production answer.

## Architecture decisions already made

- **Language/runtime:** Java 21 + Spring Boot 3.3.x.
- **Build:** Gradle (Kotlin DSL).
- **API:** HTTP/REST via Spring MVC. OpenAPI/Swagger UI via springdoc.
- **Persistence:** **SQLite** (single file at `./data/graph.db`). The DB *is* the persisted state — no separate snapshot or event log. Schema lives in `src/main/resources/schema.sql` and is applied at boot via Spring's `spring.sql.init`. No event replay on boot — repositories `SELECT` rows back into the in-memory graph and ingestion resumes from there.
- **Concurrency:** custom in-memory bounded queue (no Kafka/Redis/etc. — spec forbids), N producers + M consumers configurable via `sda.ingest.*`. Producer/consumer threads are **virtual threads** (`Thread.ofVirtual()`) — they're cheap, blocking on the queue lock or the SQLite write lock doesn't pin a platform thread.
- **Graph:** custom in-memory adjacency structure (no graph DBs / libraries — spec forbids).

## Queue design

Two layers:

- **`queue.EventQueue`** — interface for one bounded MPMC pipe. The default implementation, `ArrayBlockingQueueAdapter`, wraps `java.util.concurrent.ArrayBlockingQueue` and adds a `closed` flag, a drain protocol (`take()` returns `null` once closed-and-empty), and `QueueMetrics`.
- **`queue.PartitionedEventQueue`** — holds `consumerCount` `EventQueue` instances. Producers and the HTTP ingest endpoint publish through `publish(event)` / `tryPublish(event, timeout)`, which hashes a partition key and routes to the corresponding queue. Each consumer owns one partition.

**Why partitioning.** With a single shared queue and N consumers, two consumers can pull adjacent same-edge events and apply them in the wrong order. That doesn't break order-independent operations (rolling avg) but does mean `observed → removed → observed` flows become non-deterministic. Partitioning by `(source, target)` for edge events (and service id for service events) gives **per-edge serial ordering** — every event for a given edge goes through one consumer thread in arrival order. Cross-edge concurrency is preserved because different edges hash to different consumers.

**Backpressure policy: BLOCK (default).** `publish()` calls `put` on the chosen partition; producers self-throttle to consumer speed. A hot edge filling its own partition blocks producers writing to *that* partition while leaving others free — graceful degradation under skew. The HTTP ingest path uses `tryPublish` with a 1 s timeout so a full partition returns 503 instead of pinning a Tomcat thread. `sda.ingest.put-timeout-ms` flips internal producers to timed `tryPublish` for deliberate sheds.

**The queue is dumb on purpose.** It does not know about events beyond the type parameter (other than for partition key extraction), does not dedup, does not persist. Idempotency and out-of-order tolerance live downstream (consumer + graph layer).

**Why `ArrayBlockingQueue` and not a hand-rolled ring buffer?** Same internals (`ReentrantLock` + 2 `Condition`s + circular array), well-tested. The `EventQueue` interface keeps the swap to a lock-free MPMC queue a one-line change if contention ever becomes the bottleneck — which it won't before the SQLite single-writer becomes the bottleneck first.

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

### Out-of-order tolerance — last-write-wins, scoped to removal boundaries

- Each `Edge` tracks `lastObservedTs` — `max` over the event timestamps of every observation applied. Persisted on the `edges` row as `last_observed_ts`.
- `ServiceGraph` keeps an in-memory `tombstones: Map<EdgeKey, Instant>`. `dependency_removed` events set the tombstone for `(source, target)` to the higher of their `event.ts` and any existing tombstone.
- `dependency_observed` is rejected as stale **only** when a tombstone exists with `removedTs > event.ts` — the edge was removed at a strictly later event time, so this observation predates the removal and must not resurrect the edge.
- `dependency_observed` events on a live edge always land. Multiple consumers can pull two same-edge observations in any order; the rolling average is order-independent (it's just the mean), so accepting both produces the correct final state regardless of who wins the write lock. Rejecting the smaller-ts event would silently drop a legitimate sample whenever scheduling reorders adjacent events (fixture `health/01` regression).
- `dependency_removed` is rejected as stale when the existing edge has `lastObservedTs > event.ts` — a stale removal must not undo a newer observation. Otherwise it removes the edge and stamps the tombstone.

This makes the out-of-order policy spec-compliant (late removals are rejected; observations across a removal boundary are correctly disambiguated by the tombstone) without the false-rejection bug that a naive "every observation must be ≥ lastObservedTs" check would cause.

**Persistence boundary.** `last_observed_ts` survives restart on the `edges` table — surviving edges keep their staleness frontier so post-restart stale removals are still rejected. Tombstones for removed edges are intentionally in-memory only; a persistent `tombstones(source, target, removed_ts)` table is the production answer for surviving cross-restart stale observations on already-deleted edges.

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

Test-only packages under `src/test/java/com/groupon/sda`:

| Package | Role |
|---|---|
| `testsupport` | `FixtureLoader` (classpath-resource loader for `fixtures/*.json`), `TestStack` (open an end-to-end stack against a given SQLite path with restore replayed), `MutableClock`, `PersistenceTestSupport` |
| `restart` | `RestartConsistencyTest` |
| `concurrency` | `HotEdgeConcurrencyTest` |
| `queue` | Queue-level unit tests including `BackpressureTest` and `GracefulShutdownTest` |
| `api` | MockMvc slice tests including `ApiErrorPathTest` and `EventIngestApiErrorTest` |

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

## MCP server

`mcp/` is a TypeScript MCP (Model Context Protocol) server that wraps the six read-only graph queries as tools an LLM client (Claude Desktop / Claude Code) can call. It lives outside the Gradle build — its own `package.json` / `tsconfig.json`, built with `npm run build` to `mcp/dist/index.js`.

- **Scope:** read-only. Tools: `reachable`, `dependents`, `shortest_path`, `critical_services`, `cycles`, `health`. Event ingest is intentionally not exposed — keeps LLM-driven calls deterministic and side-effect-free.
- **Transport:** stdio. The client spawns the binary per session and pipes JSON-RPC over stdin/stdout. There is no listening port; switching to streamable-HTTP would be a code change in `mcp/src/index.ts`, not a deployment one.
- **Wiring:** thin HTTP client over `SDA_BASE_URL` (default `http://localhost:8080`, overridden to `http://sda:8080` inside the compose network). 4xx/5xx responses are surfaced to the LLM with the SDA `ApiError` envelope (`error`, `message`, `service`) plus the HTTP status, so the model can react to `service_not_found` vs `invalid_request`. Connection failures append a hint pointing at `SDA_BASE_URL`.
- **Validation:** Zod schemas per tool. Argument errors return `isError: true` with a structured Zod message rather than throwing.

Run modes:

1. **Local Node.** `cd mcp && npm install && npm run build`, then point the client at `node /abs/path/to/mcp/dist/index.js` with `SDA_BASE_URL` pointing at a running `bootRun` instance.
2. **Sidecar in compose.** `docker compose up -d` brings up `sda-mcp` (a tiny `node:22-alpine` image with the built server baked in). Stdio MCP servers don't run as daemons — the sidecar's `CMD` is `tail -f /dev/null` so the container stays alive, and the client attaches per-session via `docker exec -i sda-mcp node /app/dist/index.js`. Compose service has `stdin_open: true` for that exec path. The sidecar reaches the SDA over the compose network at `http://sda:8080` (set in `mcp/Dockerfile`).

Client-config snippets (Claude Desktop, Claude Code) for both modes live in `mcp/README.md`.

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
- Test coverage — idempotency, concurrent correctness on hot edges, algorithm correctness on hand-crafted graphs, restart consistency, backpressure (BLOCK + SHED), graceful shutdown, and API error paths are all covered by JUnit fixture-driven tests. Gaps: no scale benchmark for the "single-digit ms point queries on 10k services / 100k edges" claim, and no automated test for the `edge_samples` orphan-row issue (a sample written before a removal still rehydrates if the same edge is later re-observed across a restart) — both worth adding when time permits.
