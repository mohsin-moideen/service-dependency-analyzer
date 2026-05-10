# Hand-crafted event fixtures

Each file in this tree is a JSON array of events. The shape matches what the
batch ingest endpoint accepts:

```bash
curl -sS -X POST http://localhost:8080/api/v1/events/batch \
     -H 'Content-Type: application/json' \
     --data-binary @topology/02-diamond.json
```

Tests can also load a fixture via `ObjectMapper.readValue(..., new TypeReference<List<Event>>() {})`
and feed events directly to a `ServiceGraph` or `EventConsumer`.

The fixtures are deliberately small. Each one targets a single invariant or
query property so that when it breaks you know exactly what regressed. Larger
randomized datasets are the synthetic generator's job, not these.

## Conventions

- Anchor "now" for queries: **`2026-05-10T12:00:00Z`**. Tests using
  `Clock.fixed(...)` should pin the consumer's clock there.
- Default health window: **300 s** (matches `sda.health.window-seconds`).
- All event IDs are unique unless the fixture is explicitly testing duplicate
  handling.
- Latencies are integers; status is one of `ok | error | timeout`.

## Topology — query correctness on hand-crafted shapes

| Fixture | Shape | What it proves |
|---|---|---|
| `topology/01-linear-chain.json` | `a → b → c → d` | `reachable(a) = {b,c,d}`, `dependents(d) = {a,b,c}`, `shortest_path(a,d) = a→b→c→d, weight 60`, `cycles() = []`. |
| `topology/02-diamond.json` | `a→b→d`, `a→c→d` (latencies: a→b=10, b→d=10, a→c=50, c→d=5) | `shortest_path(a,d)` picks `a→b→d` (weight 20) over `a→c→d` (weight 55) — exercises Dijkstra's edge-weighted choice. `reachable(a) = {b,c,d}`. |
| `topology/03-fan-in-hub.json` | 5 callers → `users-db` | `dependents(users-db)` returns all 5 callers. `users-db` has in-degree 5 / out-degree 0 → `critical_services` score = 0 (terminal sink). |
| `topology/04-fan-out-hub.json` | `auth` → 5 callees | `reachable(auth)` returns all 5. `auth` has in-degree 0 / out-degree 5 → `critical_services` score = 0 (pure source). |
| `topology/05-self-loop.json` | `a → a` | `cycles()` includes `[a]`. `reachable(a)` terminates and returns `{a}` (must not infinite-loop). |
| `topology/06-2-cycle.json` | `a ↔ b` | `cycles()` returns one cycle `[a,b]` (or `[b,a]` — order is implementation-defined). `reachable(a) = {a,b}` (cycle-safe BFS). |
| `topology/07-3-cycle.json` | `a → b → c → a` | `cycles()` returns one cycle of length 3. `shortest_path(a,c)` is `a→b→c` (cycle-safe Dijkstra; non-negative weights). |
| `topology/08-overlapping-cycles.json` | 3-cycle `a→b→c→a` plus 2-cycle `b↔d` sharing `b` | `cycles()` returns both cycles. Validates the cycle algorithm doesn't merge or miss them. |
| `topology/09-pure-dag.json` | 8-edge DAG with two layers | `cycles() = []`. Multiple shortest paths converge at `users-db` (sink). |
| `topology/10-disconnected.json` | Two disjoint chains: `a→b→c` and `x→y→z` | `reachable(a)` returns only `{b,c}`, never crosses. `shortest_path(a,z) = no path`. |
| `topology/11-single-node.json` | One service, no edges (heartbeat + metadata only) | `reachable(lonely-svc) = {}`. `dependents = {}`. Validates a node can exist without any incident edges. |
| `topology/12-empty.json` | Empty event stream | All queries on any service return either an empty result or "unknown service". `cycles() = []`. Sanity check that boot doesn't NPE on an empty graph. |

## Idempotency — duplicate `event_id` handling

All three fixtures hit `EventConsumer` via the same code path; differences are
in *what* the duplicates carry.

| Fixture | Setup | Expected outcome |
|---|---|---|
| `idempotency/01-exact-duplicate.json` | Same event 3× (identical payload, identical id) | Edge `a→b` exists with `sample_count = 1`. `processed_events` has exactly one row. Cache-hit and DB-exists counters increment for the duplicates. |
| `idempotency/02-cross-producer-duplicate.json` | Two distinct ids (`dup-x-1`, `dup-x-2`), each delivered 2–3× interleaved | Edge `a→b` has `sample_count = 2`. Models the case where two virtual-thread producers race to deliver the same logical id (cache may miss on one producer's claim and hit on the other's, or vice versa) — but the dedup is by `event_id`, not by payload, so distinct ids land distinctly. |
| `idempotency/03-conflicting-duplicate.json` | Same `event_id` with three *different* payloads (`latency_ms` 10/999/1, statuses ok/error/timeout) | Edge `a→b` has `sample_count = 1` reflecting the **first** payload (latency 10, status ok). Validates first-write-wins semantics: the dedup gate runs *before* the graph mutation, so the second and third payloads are never applied. |

## Out-of-order — late and reordered events

| Fixture | Setup | Expected outcome (current "simple no-op" policy) |
|---|---|---|
| `out-of-order/01-stale-removal-before-observation.json` | Two `dependency_removed` events for edges that don't exist, then one unrelated observation | Removals are no-ops. `droppedRemovalsForUnknownEdges` counter = 2. Edge `x→y` exists. No exceptions. |
| `out-of-order/02-observed-removed-observed.json` | Observe `a→b`, remove, re-observe | Edge `a→b` is present at end with `sample_count = 1` (the second observation creates a fresh edge — the first edge was destroyed). |
| `out-of-order/03-metadata-before-edges.json` | `service_metadata` then `heartbeat` then a `dependency_observed` from that service | Node `future-svc` exists with metadata (`team=ml`, `tier=tier-2`, `region=us-west-2`), heartbeat is recorded, then the edge is added. Validates that metadata/heartbeat events materialize the node when no edges have referenced it. |
| `out-of-order/04-stale-observation-after-removal.json` ⚠️ | Observe `a→b` at T1=12:00:00Z, remove at T2=12:00:30Z, then a stale observation arrives with timestamp T1+15s=12:00:15Z (i.e. before the removal in event-time, but delivered after it). | **Under the current policy:** the stale observation re-creates the edge (graph ends with `a→b` present, `sample_count = 1`). **Under last-write-wins-by-timestamp:** the stale observation is rejected because the edge has a tombstone at 12:00:30 > 12:00:15, and the graph correctly ends with no edge. This fixture is the regression test for the REVISIT note in `CLAUDE.md`. Today it passes the relaxed policy; the day we tighten the policy, this fixture's expected outcome flips and the test should be updated to assert no edge exists. |

## Health window — `health(service, window)` semantics

All four fixtures assume `now = 2026-05-10T12:00:00Z`, `window = PT5M` (300 s),
service id `s`. The edge under test is always `s → t`.

| Fixture | Setup | Expected `health(s, PT5M)` |
|---|---|---|
| `health/01-known-p95.json` | 20 ok samples with latencies 1..20 ms, all within the last 20 s | `sample_count = 20`, `error_rate = 0.0`, `p95 = 19` ms (sorted index `ceil(0.95×20) − 1 = 18` of `[1..20]`). |
| `health/02-mixed-statuses.json` | 10 samples, all latency 100 ms: 7 ok, 2 error, 1 timeout | `sample_count = 10`, `error_rate = 0.3`, `p95 = 100` ms. |
| `health/03-window-boundary.json` | 5 samples: 11:50:00Z (out, latency 999), 11:54:00Z (out, latency 999), 11:55:00Z (in, latency 50), 11:57:30Z (in, latency 80), 12:00:00Z (in, latency 120) | `sample_count = 3`, `error_rate = 0.0`, `p95 = 120` ms. The `11:55:00Z` sample is *exactly* at the cutoff — `s.ts().isBefore(cutoff)` is false, so it's included. The two out-of-window samples are also self-evicted from the in-memory deque on the writer path because the writer's clock is at 12:00:00Z. |
| `health/04-no-in-window-samples.json` | 3 samples, all timestamped 8–10 minutes before "now" | `health(s, PT5M)` returns `null`. Validates the "no samples ⇒ null" path rather than returning a zeroed-out struct. |

## Adding a fixture

1. Pick the smallest event count that exhibits the property — 3 events is
   often enough.
2. Use deterministic timestamps anchored on `2026-05-10T12:00:00Z`.
3. Add a row to the relevant table above with the expected query answers
   spelled out, so a reviewer doesn't have to re-derive intent.
4. If the fixture exercises a policy that may change (like the REVISIT
   timestamp-comparison case), call that out explicitly and document both
   the current and the future expected outcomes.
