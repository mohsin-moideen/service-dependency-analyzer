# Service Dependency Analyzer

In-memory directed graph of service dependencies, fed by a custom event queue, with REST queries for blast radius, reverse dependencies, shortest path, criticality, cycles, and health.

A teammate-style tour of the architecture lives in [`CLAUDE.md`](./CLAUDE.md). The design write-up is in [`REPORT.md`](./REPORT.md).

---

## Quick start

```bash
./gradlew bootRun
```

That's it. The synthetic generator is on by default and seeds ~5 000 services and ~100 000 events at startup so the API has something to answer immediately.

Useful endpoints once it's running:

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- Actuator health: <http://localhost:8080/actuator/health>

If you'd rather feed your own data instead of using the generator, disable it:

```bash
./gradlew bootRun --args='--sda.events.generator.enabled=false'
```

### Build and test

```bash
./gradlew build       # compile + run all tests
./gradlew test        # tests only
./gradlew bootJar     # runnable jar at build/libs/service-dependency-analyzer-0.1.0-SNAPSHOT.jar
```

Java 21 is required. The Gradle wrapper is committed (Gradle 8.10) and the foojay toolchain resolver auto-provisions a matching JDK on first build if you don't have one.

### Docker

```bash
docker compose up -d      # builds + starts the SDA service (and the MCP sidecar)
docker compose ps         # `sda` should report healthy on :8080
docker compose down
```

The compose stack uses a multi-stage `Dockerfile` and persists state on the `sda-data` named volume so SQLite survives `down`.

### End-to-end fixture harness

```bash
./gradlew bootJar                                    # prerequisite
bash scripts/run_fixture_tests.sh                    # run every fixture
bash scripts/run_fixture_tests.sh topology/02-diamond  # filter
```

The harness boots the bootJar with the synthetic generator off, POSTs each fixture to `/api/v1/events/batch`, and diffs the API responses against the README in `src/test/resources/fixtures/`.

---

## Publishing events

The ingest API accepts the four event types defined by the spec.

**Single event:**

```bash
curl -sS -X POST http://localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "dependency_observed",
    "event_id": "e-9f2c1234",
    "timestamp": "2026-05-10T14:21:09.412Z",
    "source": "checkout-api",
    "target": "payments-service",
    "latency_ms": 42,
    "status": "ok"
  }'
```

Returns `202 Accepted` on success, `503 Service Unavailable` if the partition for that edge is full (deliberate backpressure — caller should retry).

**Batch:**

```bash
curl -sS -X POST http://localhost:8080/api/v1/events/batch \
  -H 'Content-Type: application/json' \
  -d '[
    {"type":"dependency_observed","event_id":"e-1","timestamp":"2026-05-10T12:00:00Z","source":"a","target":"b","latency_ms":10,"status":"ok"},
    {"type":"dependency_observed","event_id":"e-2","timestamp":"2026-05-10T12:00:01Z","source":"b","target":"c","latency_ms":15,"status":"ok"},
    {"type":"dependency_removed","event_id":"e-3","timestamp":"2026-05-10T12:00:02Z","source":"a","target":"b"},
    {"type":"service_metadata","event_id":"e-4","timestamp":"2026-05-10T12:00:03Z","service":"payments-service","attributes":{"team":"billing","tier":"tier-0","region":"us-east-1"}},
    {"type":"heartbeat","event_id":"e-5","timestamp":"2026-05-10T12:00:04Z","service":"checkout-api"}
  ]'
```

Returns `{"submitted": N, "accepted": N, "rejected": 0}` (HTTP 202) when everything's enqueued, or `503` with non-zero `rejected` when a partition fills up — the caller can retry the unaccepted suffix.

The four supported event types:

| Type | Required fields beyond `event_id`/`timestamp` | Effect |
|---|---|---|
| `dependency_observed` | `source`, `target`, `latency_ms`, `status` (`ok`/`error`/`timeout`) | Adds (or refreshes) edge `source → target` and contributes a sample. |
| `dependency_removed` | `source`, `target` | Removes the edge if present. Tolerant to "edge unknown" — counted, not crashed. |
| `service_metadata` | `service`, `attributes` (free-form `Map<String,String>`; `team`/`tier`/`region` are recognized) | Updates that service's metadata. |
| `heartbeat` | `service` | Stamps `last_heartbeat_ts` for the service. |

---

## Querying the API

All six analytical endpoints. Replies are JSON, errors come back as a structured `ApiError` envelope (no stack traces).

**Reachable (blast radius):**

```bash
curl -sS http://localhost:8080/api/v1/graph/reachable/checkout-api | jq .
```
```json
{
  "service": "checkout-api",
  "reachable": [
    {"id": "payments-service", "path": ["checkout-api", "payments-service"]},
    {"id": "db-primary",       "path": ["checkout-api", "payments-service", "db-primary"]}
  ]
}
```

**Dependents (reverse reachability):**

```bash
curl -sS http://localhost:8080/api/v1/graph/dependents/db-primary | jq .
```

Same shape, but paths read in the call direction toward the queried service: `[caller, ..., db-primary]`.

**Shortest path (lowest rolling-avg latency):**

```bash
curl -sS 'http://localhost:8080/api/v1/graph/shortest-path?source=checkout-api&target=db-primary' | jq .
```
```json
{
  "source": "checkout-api",
  "target": "db-primary",
  "found": true,
  "path": ["checkout-api", "payments-service", "db-primary"],
  "totalLatencyMs": 27.4
}
```

`found: false` with an empty path means the endpoints exist but no directed path connects them.

**Top-k critical services:**

```bash
curl -sS 'http://localhost:8080/api/v1/graph/critical-services?k=5' | jq .
```
```json
{
  "k": 5,
  "services": [
    {"id": "db-primary",    "score": 84.0, "inDegree": 12, "outDegree": 7},
    {"id": "auth-service",  "score": 50.0, "inDegree": 10, "outDegree": 5}
  ]
}
```

**Cycles (every elementary cycle):**

```bash
curl -sS http://localhost:8080/api/v1/graph/cycles | jq .
```
```json
{
  "cycles": [
    ["a", "b", "a"],
    ["a", "b", "c", "a"]
  ]
}
```

Each entry is a closed cycle (first element repeated as the last). A self-loop on `v` is reported as `["v", "v"]`.

**Health (error rate + p95 over a trailing window):**

```bash
curl -sS 'http://localhost:8080/api/v1/graph/health/payments-service?windowSeconds=300' | jq .
```
```json
{
  "service": "payments-service",
  "windowSeconds": 300,
  "sampleCount": 312,
  "errorRate": 0.014,
  "p95LatencyMs": 87
}
```

`windowSeconds` defaults to `sda.health.window-seconds` (300). Asking for a window larger than the configured retention returns `400 invalid_request`.

**Error responses** for every endpoint use the same shape:

```json
{ "error": "service_not_found", "message": "unknown service: foo", "service": "foo" }
```

Mapping:

| HTTP | `error` | When |
|---|---|---|
| 404 | `service_not_found` | Path or query parameter names a service that isn't in the graph |
| 400 | `invalid_request` | Bad params (`k <= 0`, `windowSeconds > retention`, malformed JSON) |
| 503 | `service_unavailable` | Queue full at ingest, or shutdown in progress |
| 500 | `internal_error` | Anything unhandled — caller sees a generic message, full trace is in the logs |

---

## Configuration

Knobs in `src/main/resources/application.yml` under the `sda.*` namespace. Anything below can be overridden on the command line as `--sda.foo.bar=value`.

### Ingestion

| Key | Default | Purpose |
|---|---|---|
| `sda.ingest.producer-count` | `2` | Internal producer threads pulling from the synthetic generator. |
| `sda.ingest.consumer-count` | `2` | Consumer threads. **Also the number of queue partitions** — events for one `(source, target)` pair always land in the same partition, giving per-edge serial ordering. |
| `sda.ingest.queue-capacity` | `10000` | Bounded capacity *per partition*. Total in-flight headroom = `consumer-count × queue-capacity`. |
| `sda.ingest.put-timeout-ms` | `0` (off) | When `> 0`, internal producers use a timed `offer` and shed-with-counter on timeout instead of blocking. The HTTP ingest path always uses a 1 s timed offer (returns 503 on timeout) regardless of this knob. |
| `sda.ingest.dedup-cache-capacity` | `200000` | Sizing hint for the in-memory `SeenEventsCache`. Ignored by the exact-set default; used by a future bloom-filter implementation. |

### Health window

| Key | Default | Purpose |
|---|---|---|
| `sda.health.window-seconds` | `300` | Default trailing window for `health()` queries; also caps how long per-edge samples are retained in memory. |
| `sda.health.max-samples-per-edge` | `1024` | Hard cap on the per-edge sample deque. Protects the heap if a single edge gets pathologically hot. |

### Synthetic generator

| Key | Default | Purpose |
|---|---|---|
| `sda.events.generator.enabled` | `true` | Master switch. Set to `false` to ingest only via the HTTP API. |
| `sda.events.generator.service-count` | `5000` | Distinct service ids fabricated. |
| `sda.events.generator.event-count` | `100000` | Total events emitted. |
| `sda.events.generator.hub-count` | `8` | Services that receive a disproportionate share of incoming traffic (databases, auth, cache). |
| `sda.events.generator.cycles-to-inject` | `3` | Explicit cycles seeded into the topology. |
| `sda.events.generator.error-rate` | `0.05` | Fraction of `dependency_observed` events with non-OK status. |
| `sda.events.generator.duplicate-rate` | `0.01` | Fraction of events that are exact replays of a recent event id (drives the dedup path during testing). |
| `sda.events.generator.seed` | `42` | RNG seed for reproducibility. |

### Database

| Key | Default | Purpose |
|---|---|---|
| `spring.datasource.url` | `jdbc:sqlite:./data/graph.db` | SQLite file path. |
| `spring.datasource.hikari.maximum-pool-size` | computed (`consumer-count + 5`) | Set programmatically in `IngestConfig.dataSource(...)` so the pool can never undersize relative to consumers. |

A `@Scheduled(fixedDelay=PT1M)` task in `PersistenceMaintenance` prunes `edge_samples` older than `health.window-seconds`. There is **no separate snapshot cadence** — the DB is the persisted state, and every event commits its updates inside a transaction, so the rolling state is always durable.

---

## Assumptions and constraints

The spec gave us guardrails (no Kafka, no Neo4j, etc.). These are the **choices we made within those guardrails**, plus the assumptions our defaults bake in. Worth knowing if you push the system anywhere unusual.

**Single JVM.** Everything runs in one process: producers, queue, consumers, the graph, the API. No clustering, no cross-process state. Horizontal scaling is in the report's "future work" — see [`REPORT.md`](./REPORT.md). The `≥ 2 consumers` requirement from the spec is satisfied by the in-process partitioning model.

**SQLite is the persisted state.** `services` / `edges` / `edge_samples` / `processed_events` *are* the snapshot; we don't keep an event log on disk. Every event commit is a transaction, and on boot we `SELECT` the rows back into the in-memory graph. No event replay.

**Backpressure default is BLOCK.** A `dependency_removed` we silently dropped would corrupt the graph for the rest of the run, so we'd rather slow producers down than lose events. Set `sda.ingest.put-timeout-ms > 0` if you'd rather shed deliberately; you'll see a structured WARN per drop.

**Graceful shutdown drains.** SIGTERM triggers `ProducerManager.stop()` (closes every partition first so blocked `put`s wake up, then joins producers), then `ConsumerManager.stop()` (waits for consumers to drain `take() == null` and exit). Spring's `server.shutdown: graceful` covers in-flight HTTP. We don't expose a forced "drop everything now" toggle.

**Out-of-order tolerance is last-write-wins by event timestamp**, scoped to removal boundaries. A `dependency_observed` is rejected only when a tombstone for `(source, target)` exists with `removedTs > event.ts` (the edge was removed at a strictly later event time). A `dependency_removed` is rejected when the existing edge has `lastObservedTs > event.ts`. Two same-edge observations in any arrival order both land — the rolling average is order-independent, so accepting both is correct.

**Tombstones are in-memory only.** They survive the lifetime of a JVM but are lost on restart. `last_observed_ts` *is* persisted on the `edges` row, so post-restart stale removals on surviving edges are still rejected. A persistent `tombstones(source, target, removed_ts)` table is the production answer for surviving cross-restart stale observations on already-deleted edges.

**Idempotency is a two-layer gate**: an in-memory `ConcurrentHashMap.newKeySet()`-backed cache (atomic `tryClaim`) plus the `processed_events` table's primary key constraint. The cache resolves the race within one JVM; the table covers restarts.

**Sample retention is double-bounded** per edge: by count (`max-samples-per-edge`, default 1024) *and* by age (`health.window-seconds`). Eviction uses an injected `Clock` (default `Clock.systemUTC()`), not event timestamps — that way a stale or future-dated event can't poison the eviction reference.

**Cycles are returned as elementary cycles** (DFS from each vertex with a lex-min start constraint). Two cycles sharing a vertex but distinct in their edge sets — e.g., a 3-cycle and a 2-cycle through the same hub — are reported separately. Self-loops are reported as `[v, v]`.

**Critical-services metric is a hybrid score**: `score(v) = inDegree(v) × outDegree(v)`. We chose this over Brandes' betweenness centrality for runtime cost (`O(V)` vs `O(V·E)`) and intuitive interpretability. See [`REPORT.md`](./REPORT.md) for the full trade-off.

**Health window cannot exceed the configured retention** (`health.window-seconds`). Asking for a longer window yields `400 invalid_request` rather than silently truncating.

**No persistent event log.** Lost in-flight events on a hard crash (between in-memory mutation and DB commit) are gone — the synthetic generator doesn't re-deliver, and the queue isn't durable. At-least-once delivery would require a durable upstream (Kafka), which the spec forbids. Documented; accepted scope.

**Java 21 + Spring Boot 3.3.x.** Producer/consumer threads are virtual (`Thread.ofVirtual()`) — they're cheap and blocking on the queue lock or SQLite write lock doesn't pin a platform thread.

---

## MCP server

[`mcp/`](./mcp) contains a TypeScript MCP server that exposes the six read queries (`reachable`, `dependents`, `shortest_path`, `critical_services`, `cycles`, `health`) as tools an LLM client (Claude Desktop / Claude Code) can call. Read-only by design — event ingest is intentionally not exposed. Talks to a running SDA over HTTP (`SDA_BASE_URL`, default `http://localhost:8080`).

Two ways to run it:

**Local Node** — `cd mcp && npm install && npm run build`, then point your MCP client at `node /abs/path/to/mcp/dist/index.js`.

**Sidecar in Docker Compose** — `docker compose up -d` brings up both `sda` and an `sda-mcp` container preloaded with the built server. Stdio MCP servers are spawned per-session by the client, so the sidecar stays idle until something attaches via `docker exec -i sda-mcp node /app/dist/index.js`. The sidecar reaches the service over the compose network at `http://sda:8080`.

Full setup, env vars, and Claude Desktop / Code config snippets in [`mcp/README.md`](./mcp/README.md).
