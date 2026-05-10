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

if cache.contains(event.id):
    if processedEventsRepo.exists(event.id): // confirm against authoritative store
        metrics.duplicates++
        continue
    // BF false positive — fall through

tx.begin()
graphMutationRepo.apply(event)               // edges/services row writes
processedEventsRepo.insert(event.id)
tx.commit()

graph.apply(event)                           // in-memory mutation, AFTER db commit
cache.add(event.id)
```

In-memory graph mutation happens after the DB commit so in-memory state is always derivable from durable state — restart re-reads the DB into the graph and stays consistent.

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

Boot sequence: Spring opens the SQLite file, runs `schema.sql` (CREATE TABLE IF NOT EXISTS), then `persistence` repositories `SELECT` rows back into the in-memory graph. After that, every event commits its row updates inside a transaction — no replay needed because the DB is always current.

SQLite serializes writes; route all writes through a single writer thread (or accept short transactional contention) and rely on WAL mode for read concurrency.

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
- `health.window-seconds`

## What's still placeholder (build these out)

- `graph.ServiceGraph` — empty class with TODOs.
- `graph.algorithms.*` — not started; implement BFS reachable/dependents, Dijkstra (or similar) for shortest path, Tarjan/Johnson for cycles, betweenness or similar for `critical_services`.
- `ingest.*` — producers/consumers and shutdown wiring.
- `persistence.*` — SQLite repositories.
- `events.generator.EventGenerator` — synthetic dataset generator (~5–10k services, ~50–200k events, with cycles, fan-in hubs, error tail).
- Tests — only a placeholder unit test exists. The spec calls out: idempotency, concurrent correctness, query correctness on hand-crafted graphs (incl. cycles), restart consistency.
- Observability — structured logs and metrics (queue depth, events processed, query latency histograms) are encouraged.
