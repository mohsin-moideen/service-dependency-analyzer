# Hand-crafted event fixtures

Each file is a JSON array of events shaped for the batch-ingest endpoint:

```bash
curl -sS -X POST http://localhost:8080/api/v1/events/batch \
     -H 'Content-Type: application/json' \
     --data-binary @topology/02-diamond.json
```

Tests load fixtures via `FixtureLoader.load(...)` and feed events directly to a
`ServiceGraph`, an `EventConsumer`, or (for the negative fixtures) MockMvc.

The fixtures are deliberately small. Each one targets a single invariant or
query property so that when it breaks you know exactly what regressed. Larger
randomized data is the synthetic generator's job, not these.

## Conventions

- Anchor "now" for queries: **`2026-05-10T12:00:00Z`**. Tests using `Clock.fixed(...)`
  pin the consumer's clock here.
- Default health window: **300 s** (matches `sda.health.window-seconds`).
- All event IDs are unique unless the fixture is testing duplicate handling.
- Latencies are integers; status is one of `ok | error | timeout`.
- All event types are valid wire-format JSON. Negative fixtures are kept in a
  separate `negative/` directory and are *not* expected to deserialize cleanly —
  see [Negative](#negative--api-error-paths) below.

## Test-class index

| Fixture path | Consumed by |
|---|---|
| `topology/*` | `ReachabilityTest`, `ShortestPathTest`, `CyclesTest`, `CriticalityTest` (algorithm slice tests; some still inline-build their graphs) |
| `idempotency/*` | `EventConsumerIdempotencyTest` |
| `out-of-order/*` | `ServiceGraphTest` (LWW invariants) |
| `health/*` | `EdgeTest`, `ServiceGraphTest` |
| `concurrency/01-hot-edge.json` | `HotEdgeConcurrencyTest` |
| `restart/01-rich-mixed-stream.json` | `RestartConsistencyTest` |
| `metadata/01-incremental-merge.json` | `ServiceGraphTest`, `ServiceRepositoryTest` |
| `negative/*` | `EventIngestApiErrorTest` |

## Topology — query correctness on hand-crafted shapes

| Fixture | Shape | What it proves |
|---|---|---|
| `topology/01-linear-chain.json` | `a → b → c → d` | `reachable(a) = {b,c,d}`, `dependents(d) = {a,b,c}`, `shortest_path(a,d) = a→b→c→d, weight 60`, `cycles() = []`. |
| `topology/02-diamond.json` | `a→b→d`, `a→c→d` (latencies a→b=10, b→d=10, a→c=50, c→d=5) | `shortest_path(a,d)` picks `a→b→d` (weight 20) over `a→c→d` (weight 55). Validates that Dijkstra weights edges, not hops. `reachable(a) = {b,c,d}`. |
| `topology/03-fan-in-hub.json` | 5 callers → `users-db` | `dependents(users-db)` returns all 5 callers. `users-db` has in-deg 5 / out-deg 0 → criticality score = 0 (terminal sink). Every caller has score 0 too — see `topology/14-tied-criticality.json` for a real top-k test. |
| `topology/04-fan-out-hub.json` | `auth` → 5 callees | `reachable(auth)` returns all 5. `auth` has in-deg 0 / out-deg 5 → criticality score = 0 (pure source). |
| `topology/05-self-loop.json` | `a → a` | `cycles()` includes `[a, a]`. `reachable(a)` returns `[a]` with path `[a, a]` (the spec's "service whose failure can affect itself" semantics) — must not infinite-loop. |
| `topology/06-2-cycle.json` | `a ↔ b` | `cycles()` returns `[a, b, a]`. `reachable(a)` includes both `a` (path `[a,b,a]`) and `b` (path `[a,b]`). Cycle-safe BFS. |
| `topology/07-3-cycle.json` | `a → b → c → a` | `cycles()` returns one cycle of length 4 (`[a,b,c,a]`). `shortest_path(a,c)` is `[a,b,c]`, weight 20. Dijkstra is correct on graphs with cycles because all weights are non-negative. |
| `topology/08-overlapping-cycles.json` | 3-cycle `a→b→c→a` plus 2-cycle `b↔d` sharing node `b` | `cycles()` returns both elementary cycles: `[b,d,b]` (length 3) and `[a,b,c,a]` (length 4). Validates `Cycles.enumerateElementaryCycles` enumerates *every* cycle, not just one per SCC. |
| `topology/09-pure-dag.json` | 8-edge DAG with two layers | `cycles() = []`. Multiple paths converge at `users-db` (sink). |
| `topology/10-disconnected.json` | Two disjoint chains: `a→b→c` and `x→y→z` | `reachable(a) = {b,c}`, never crosses. `shortest_path(a,z).found = false`. |
| `topology/11-single-node.json` | One service, no edges (heartbeat + metadata only) | `reachable(lonely-svc) = []`. `dependents = []`. Validates a node can exist without any incident edges. |
| `topology/12-empty.json` | Empty event stream | All queries on any service return either an empty result or `service_not_found`. `cycles() = []`. Sanity check that boot doesn't NPE on an empty graph. |
| `topology/13-tied-shortest-paths.json` | Diamond `a→b→d`, `a→c→d` with **all four edges latency = 10** | Two paths of equal weight (20). `shortest_path(a,d)` must return *one* of them deterministically; the test asserts `path` and `totalLatencyMs` are stable across re-runs. |
| `topology/14-tied-criticality.json` | Two identical fan-in/out hubs `alpha` (in=2, out=2) and `beta` (in=2, out=2) | Both have score 4. `critical_services(k=2)` returns `[alpha, beta]` by lex tiebreak (per `Criticality`'s `thenComparing(CriticalService::id)`). |

## Idempotency — duplicate `event_id` handling

| Fixture | Setup | Expected outcome |
|---|---|---|
| `idempotency/01-exact-duplicate.json` | Same event 3× (identical payload, identical id) | Edge `a→b` exists with `sample_count = 1`. `processed_events` has exactly one row. The cache-hit and DB-exists counters increment for the duplicates. |
| `idempotency/02-cross-producer-duplicate.json` | Two distinct ids (`dup-x-1`, `dup-x-2`), each delivered 2–3× interleaved | Edge `a→b` has `sample_count = 2`. Models the case where two virtual-thread producers race to deliver the same logical id — dedup is by `event_id`, so distinct ids land distinctly. |
| `idempotency/03-conflicting-duplicate.json` | Same `event_id` with three *different* payloads (`latency_ms` 10/999/1, statuses ok/error/timeout) | Edge `a→b` has `sample_count = 1` reflecting the **first** payload. Validates first-write-wins: the dedup gate runs *before* the graph mutation, so the second and third payloads never touch the graph. |

## Out-of-order — LWW (last-write-wins by event timestamp)

LWW is implemented in `ServiceGraph` via per-edge `lastObservedTs` plus an
in-memory `tombstones` map. These fixtures pin the contract.

| Fixture | Setup | Expected outcome |
|---|---|---|
| `out-of-order/01-stale-removal-before-observation.json` | Two `dependency_removed` events for edges that don't exist, then one unrelated observation | Removals are no-ops at the edge level but **stamp tombstones** so a later stale observation gets rejected. `droppedRemovalsForUnknownEdges` counter = 2. Edge `x→y` exists. No exceptions. |
| `out-of-order/02-observed-removed-observed.json` | Observe `a→b` at T1, remove at T2 > T1, re-observe at T3 > T2 | Edge `a→b` is present at end with `sample_count = 1` (T3 observation creates a fresh edge — T3 > tombstone(T2), so it's accepted). |
| `out-of-order/03-metadata-before-edges.json` | `service_metadata` then `heartbeat` then a `dependency_observed` from that service | Node `future-svc` materializes from the metadata event with `team=ml`, `tier=tier-2`, `region=us-west-2`. Heartbeat is recorded. The edge is added afterward. Validates that node-creation is on the metadata/heartbeat path, not just the observed-edge path. |
| `out-of-order/04-stale-observation-after-removal.json` | Observe at T1=12:00:00Z, remove at T2=12:00:30Z, then a stale observation arrives with timestamp T1+15s = 12:00:15Z (older than the removal in event-time, but delivered after it) | Stale observation is **rejected** because tombstone is at 12:00:30Z > 12:00:15Z. Graph ends with no `a→b` edge. `droppedStaleObservations` counter increments. This is the regression test that originally motivated the LWW upgrade — if anyone reverts to the simple no-op policy, this fixture flips red. |

## Health window — `health(service, window)` semantics

Anchor `now = 2026-05-10T12:00:00Z`, `window = PT5M` (300 s). Service id `s`,
edge `s → t`.

| Fixture | Setup | Expected `health(s, PT5M)` |
|---|---|---|
| `health/01-known-p95.json` | 20 ok samples with latencies 1..20 ms, all within the last 20 s | `sample_count = 20`, `error_rate = 0.0`, `p95 = 19` ms (sorted index `ceil(0.95×20) − 1 = 18` of `[1..20]`). |
| `health/02-mixed-statuses.json` | 10 samples, all latency 100 ms: 7 ok, 2 error, 1 timeout | `sample_count = 10`, `error_rate = 0.3`, `p95 = 100` ms. |
| `health/03-window-boundary.json` | 5 samples: 11:50:00Z (out, latency 999), 11:54:00Z (out, latency 999), 11:55:00Z (in, latency 50), 11:57:30Z (in, latency 80), 12:00:00Z (in, latency 120) | `sample_count = 3`, `error_rate = 0.0`, `p95 = 120` ms. The 11:55:00Z sample is *exactly* at the cutoff — `s.ts().isBefore(cutoff)` is false, so it's included. The two out-of-window samples are also self-evicted from the in-memory deque on the writer path because the writer's clock is at 12:00:00Z. |
| `health/04-no-in-window-samples.json` | 3 samples, all timestamped 8–10 minutes before "now" | `health(s, PT5M)` returns `null` from `ServiceGraph.computeHealth`; the controller turns that into a zeroed-out `HealthResponse(s, 300, 0, 0.0, 0)` (HTTP 200). Validates the "no samples" path doesn't 404. |

## Metadata merge

| Fixture | Setup | Expected outcome |
|---|---|---|
| `metadata/01-incremental-merge.json` | Three sequential `service_metadata` events for `merging-svc`, each carrying *only one* attribute (`team` then `region` then `tier`) | After all three are applied, the node has `team=platform`, `region=us-east-1`, `tier=tier-1`. Validates `applyServiceMetadata`'s "null fields leave existing unchanged" semantics and `ServiceRepository.upsertMetadata`'s `COALESCE` per-column merge. |

## Concurrency

| Fixture | Setup | Expected outcome |
|---|---|---|
| `concurrency/01-hot-edge.json` | 50 distinct `event_id`s all observing `a→b`, latencies cycling 10..18 ms, 10% error rate | Driven by `HotEdgeConcurrencyTest`: 8 writer threads dispatch the events twice each (so duplicates race against fresh writes), 8 reader threads run `reachable`/`health` queries simultaneously. Final state: `edgeCount = 1`, `sample_count = 50`, 5 errors, `errorRate = 0.1`, no exceptions, no deadlock. |

## Restart

| Fixture | Setup | Expected outcome |
|---|---|---|
| `restart/01-rich-mixed-stream.json` | ~35 events: 8 metadata, 4 heartbeats, 12 plain observations, a planted `orders-api ↔ checkout-api` cycle, 3 latency outliers (timeout / error / outlier-ok), 1 observe-then-remove pair, 1 deliberate duplicate, plus late metadata + heartbeats. Hubs are `auth` (fan-out) and `users-db` (fan-in) | Used by `RestartConsistencyTest`: ingest into a fresh stack, snapshot all six query results, close the stack, re-open against the same SQLite file (which triggers the GraphRestoreRunner-equivalent rebuild from `services` / `edges` / `edge_samples`), and re-query. Every answer must match. |

## Negative — API error paths

These files are **not** valid wire-format. They exist so the API tests can post
known-bad inputs and assert structured error responses (no stack traces) per the
spec's *"Returns structured errors (unknown service, malformed request, etc.) —
not stack traces."*

| Fixture | What's wrong | Expected response |
|---|---|---|
| `negative/01-malformed-json.json` | Truncated JSON (missing closing `}` and `]`) | 400, `{"error":"invalid_request","message":"malformed request body"}` |
| `negative/02-missing-required-field.json` | Observed event without `latency_ms` | 4xx with structured `ApiError` body (Jackson may default the int to 0 — the test accepts either rejection or acceptance, but never a stack trace) |
| `negative/03-bad-status-enum.json` | `"status": "OK"` (must be lowercase `ok`) | 400, `{"error":"invalid_request"}` |
| `negative/04-unknown-event-type.json` | `"type": "fancy_new_event"` not in the `@JsonSubTypes` registry | 400, `{"error":"invalid_request"}` |
| `negative/05-bad-timestamp.json` | `"timestamp": "yesterday"` — not parseable as `Instant` | 400, `{"error":"invalid_request"}` |

## Adding a fixture

1. Pick the smallest event count that exhibits the property — 3 events is
   often enough.
2. Use deterministic timestamps anchored on `2026-05-10T12:00:00Z`.
3. Add a row to the relevant table above with the expected query answers
   spelled out.
4. If the fixture pins a behaviour that may change (a policy upgrade, a metric
   redefinition), call that out explicitly and document the current vs. future
   expected outcomes — like `out-of-order/04`'s LWW pinning above.
5. Link the fixture to its consuming test class in the [Test-class index](#test-class-index).
