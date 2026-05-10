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
- **Concurrency:** custom in-memory bounded queue (no Kafka/Redis/etc. — spec forbids), N producers + M consumers configurable via `sda.ingest.*`.
- **Graph:** custom in-memory adjacency structure (no graph DBs / libraries — spec forbids).

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
- `health.window-seconds`

## What's still placeholder (build these out)

- `queue.EventQueue` — only the interface exists.
- `graph.ServiceGraph` — empty class with TODOs.
- `graph.algorithms.*` — not started; implement BFS reachable/dependents, Dijkstra (or similar) for shortest path, Tarjan/Johnson for cycles, betweenness or similar for `critical_services`.
- `ingest.*` — producers/consumers and shutdown wiring.
- `persistence.*` — SQLite repositories.
- `events.generator.EventGenerator` — synthetic dataset generator (~5–10k services, ~50–200k events, with cycles, fan-in hubs, error tail).
- Tests — only a placeholder unit test exists. The spec calls out: idempotency, concurrent correctness, query correctness on hand-crafted graphs (incl. cycles), restart consistency.
- Observability — structured logs and metrics (queue depth, events processed, query latency histograms) are encouraged.
