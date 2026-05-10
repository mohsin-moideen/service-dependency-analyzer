#!/usr/bin/env bash
# Boots the app once per fixture (fresh DB), applies events via the batch endpoint,
# and checks API responses against the README's expected outcomes.
set -u

PROJECT_DIR="/Users/mmoideent/workspace/Service Dependency Analyzer"
JAR="$PROJECT_DIR/build/libs/service-dependency-analyzer-0.1.0-SNAPSHOT.jar"
JAVA="/Users/mmoideent/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java"
FIXTURES="$PROJECT_DIR/src/test/resources/fixtures"
WORKDIR="/tmp/sda_test"
LOGFILE="$WORKDIR/server.log"
PORT=8080
BASE="http://localhost:$PORT"

mkdir -p "$WORKDIR"
PASS=()
FAIL=()
SERVER_PID=""

# Optional first arg: fixture filter. Matches against either the test name
# (e.g. "topology/02-diamond") or the fixture file path. Substring match.
# If empty, run everything.
FILTER="${1:-}"

start_app() {
  rm -rf "$WORKDIR/data"
  mkdir -p "$WORKDIR/data"
  : > "$LOGFILE"
  cd "$WORKDIR"
  "$JAVA" -jar "$JAR" \
      --server.port=$PORT \
      --spring.datasource.url="jdbc:sqlite:$WORKDIR/data/graph.db" \
      --sda.events.generator.enabled=false \
      --sda.health.window-seconds=86400 \
      --logging.level.com.groupon.sda=WARN \
      > "$LOGFILE" 2>&1 &
  SERVER_PID=$!
  cd - >/dev/null
  for i in $(seq 1 80); do
    code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/v1/graph/cycles" 2>/dev/null || true)
    if [[ "$code" == "200" ]]; then return 0; fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "ERROR: server process died; tail of log:"; tail -30 "$LOGFILE"; return 1
    fi
    sleep 0.5
  done
  echo "ERROR: server failed to be ready; tail of log:"; tail -30 "$LOGFILE"; return 1
}

stop_app() {
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
    for i in $(seq 1 30); do
      if ! kill -0 "$SERVER_PID" 2>/dev/null; then break; fi
      sleep 0.2
    done
    kill -9 "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
    SERVER_PID=""
  fi
  # extra safety against stragglers
  pkill -f "service-dependency-analyzer-0.1.0-SNAPSHOT.jar" 2>/dev/null || true
  for i in $(seq 1 20); do
    if ! lsof -i :$PORT 2>/dev/null | grep -q LISTEN; then return 0; fi
    sleep 0.2
  done
}

post_fixture() {
  curl -sS -X POST "$BASE/api/v1/events/batch" \
    -H 'Content-Type: application/json' \
    --data-binary @"$1"
  echo
  sleep 0.5
}

# Convenience: ids reachable, sorted
reach_ids() { curl -sS "$BASE/api/v1/graph/reachable/$1" | jq -c '[.reachable[].id]|sort'; }
deps_ids()  { curl -sS "$BASE/api/v1/graph/dependents/$1" | jq -c '[.reachable[].id]|sort'; }
sp()        { curl -sS "$BASE/api/v1/graph/shortest-path?source=$1&target=$2"; }
cycles()    { curl -sS "$BASE/api/v1/graph/cycles"; }
crit()      { curl -sS "$BASE/api/v1/graph/critical-services?k=$1"; }
health()    { curl -sS "$BASE/api/v1/graph/health/$1?windowSeconds=$2"; }

check() {
  local label=$1 actual=$2 expected=$3
  if [[ "$actual" == "$expected" ]]; then
    echo "  PASS $label"; PASS+=("$label")
  else
    echo "  FAIL $label"
    echo "       expected: $expected"
    echo "       actual:   $actual"
    FAIL+=("$label")
  fi
}

run() {  # run NAME FIXTURE_FILE BLOCK
  if [[ -n "$FILTER" && "$1" != *"$FILTER"* && "$2" != *"$FILTER"* ]]; then
    return
  fi
  echo "=== $1 ==="
  start_app || { FAIL+=("$1: server-not-ready"); return; }
  post_fixture "$2"
  eval "$3"
  stop_app
}

# For negative fixtures we want to see the HTTP status + body without the
# accept/reject summary that post_fixture otherwise prints, and we don't want
# the script to fail just because the body isn't a BatchAck JSON.
post_negative() {
  curl -sS -o /tmp/sda_neg_body -w "%{http_code}" -X POST "$BASE/api/v1/events/batch" \
    -H 'Content-Type: application/json' --data-binary @"$1"
}

# --------------------------- TOPOLOGY ---------------------------

run "topology/01-linear-chain" "$FIXTURES/topology/01-linear-chain.json" '
  check "01 reachable(a)"   "$(reach_ids a)" "[\"b\",\"c\",\"d\"]"
  check "01 dependents(d)"  "$(deps_ids d)"  "[\"a\",\"b\",\"c\"]"
  check "01 sp(a,d).path"   "$(sp a d | jq -c .path)"             "[\"a\",\"b\",\"c\",\"d\"]"
  check "01 sp(a,d).weight" "$(sp a d | jq -r .totalLatencyMs)"   "60.0"
  check "01 cycles==[]"     "$(cycles | jq -c .cycles)" "[]"
'

run "topology/02-diamond" "$FIXTURES/topology/02-diamond.json" '
  check "02 sp(a,d).path"   "$(sp a d | jq -c .path)"             "[\"a\",\"b\",\"d\"]"
  check "02 sp(a,d).weight" "$(sp a d | jq -r .totalLatencyMs)"   "20.0"
  check "02 reachable(a)"   "$(reach_ids a)" "[\"b\",\"c\",\"d\"]"
'

run "topology/03-fan-in-hub" "$FIXTURES/topology/03-fan-in-hub.json" '
  cnt=$(deps_ids users-db | jq length)
  check "03 dependents(users-db).count" "$cnt" "5"
  c=$(crit 50)
  ud_score=$(echo "$c" | jq -r ".services[]|select(.id==\"users-db\")|.score")
  check "03 crit(users-db).score" "$ud_score" "0.0"
'

run "topology/04-fan-out-hub" "$FIXTURES/topology/04-fan-out-hub.json" '
  cnt=$(reach_ids auth | jq length)
  check "04 reachable(auth).count" "$cnt" "5"
  c=$(crit 50)
  auth_score=$(echo "$c" | jq -r ".services[]|select(.id==\"auth\")|.score")
  check "04 crit(auth).score" "$auth_score" "0.0"
'

run "topology/05-self-loop" "$FIXTURES/topology/05-self-loop.json" '
  cyc=$(cycles | jq -c .cycles)
  echo "  cycles: $cyc"
  has_self=$(echo "$cyc" | jq -c "any(.[]; (.|length==1 and .[0]==\"a\") or (.|length==2 and .[0]==\"a\" and .[1]==\"a\"))")
  check "05 cycles_includes_self_a" "$has_self" "true"
  check "05 reachable(a)" "$(reach_ids a)" "[\"a\"]"
'

run "topology/06-2-cycle" "$FIXTURES/topology/06-2-cycle.json" '
  cyc=$(cycles | jq -c .cycles)
  echo "  cycles: $cyc"
  cnt=$(echo "$cyc" | jq length)
  check "06 cycles.count==1" "$cnt" "1"
  check "06 reachable(a)"  "$(reach_ids a)"  "[\"a\",\"b\"]"
'

run "topology/07-3-cycle" "$FIXTURES/topology/07-3-cycle.json" '
  cyc=$(cycles | jq -c .cycles)
  echo "  cycles: $cyc"
  cnt=$(echo "$cyc" | jq length)
  check "07 cycles.count==1" "$cnt" "1"
  check "07 sp(a,c).path"   "$(sp a c | jq -c .path)" "[\"a\",\"b\",\"c\"]"
'

run "topology/08-overlapping-cycles" "$FIXTURES/topology/08-overlapping-cycles.json" '
  cyc=$(cycles | jq -c .cycles)
  echo "  cycles: $cyc"
  cnt=$(echo "$cyc" | jq length)
  check "08 cycles.count==2" "$cnt" "2"
'

run "topology/09-pure-dag" "$FIXTURES/topology/09-pure-dag.json" '
  check "09 cycles==[]" "$(cycles | jq -c .cycles)" "[]"
'

run "topology/10-disconnected" "$FIXTURES/topology/10-disconnected.json" '
  check "10 reachable(a)" "$(reach_ids a)" "[\"b\",\"c\"]"
  spr=$(sp a z)
  echo "  sp(a,z): $spr"
  found=$(echo "$spr" | jq -r .found)
  check "10 sp(a,z).found==false" "$found" "false"
'

run "topology/11-single-node" "$FIXTURES/topology/11-single-node.json" '
  check "11 reachable(lonely-svc)"  "$(reach_ids lonely-svc)" "[]"
  check "11 dependents(lonely-svc)" "$(deps_ids lonely-svc)"  "[]"
'

run "topology/12-empty" "$FIXTURES/topology/12-empty.json" '
  check "12 cycles==[]" "$(cycles | jq -c .cycles)" "[]"
'

# --------------------------- IDEMPOTENCY ---------------------------

run "idempotency/01-exact-duplicate" "$FIXTURES/idempotency/01-exact-duplicate.json" '
  h=$(health a 86400)
  echo "  health(a): $h"
  sc=$(echo "$h" | jq -r .sampleCount)
  check "id-01 sample_count==1" "$sc" "1"
'

run "idempotency/02-cross-producer-duplicate" "$FIXTURES/idempotency/02-cross-producer-duplicate.json" '
  h=$(health a 86400)
  echo "  health(a): $h"
  sc=$(echo "$h" | jq -r .sampleCount)
  check "id-02 sample_count==2" "$sc" "2"
'

run "idempotency/03-conflicting-duplicate" "$FIXTURES/idempotency/03-conflicting-duplicate.json" '
  h=$(health a 86400)
  echo "  health(a): $h"
  sc=$(echo "$h" | jq -r .sampleCount)
  er=$(echo "$h" | jq -r .errorRate)
  check "id-03 sample_count==1" "$sc" "1"
  check "id-03 errorRate==0"    "$er" "0.0"
'

# --------------------------- OUT OF ORDER ---------------------------

run "out-of-order/01-stale-removal-before-observation" "$FIXTURES/out-of-order/01-stale-removal-before-observation.json" '
  check "ooo-01 reachable(x)" "$(reach_ids x)" "[\"y\"]"
'

run "out-of-order/02-observed-removed-observed" "$FIXTURES/out-of-order/02-observed-removed-observed.json" '
  check "ooo-02 reachable(a)" "$(reach_ids a)" "[\"b\"]"
  h=$(health a 86400)
  echo "  health(a): $h"
  sc=$(echo "$h" | jq -r .sampleCount)
  check "ooo-02 sample_count==1" "$sc" "1"
'

run "out-of-order/03-metadata-before-edges" "$FIXTURES/out-of-order/03-metadata-before-edges.json" '
  code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/v1/graph/reachable/future-svc")
  check "ooo-03 future-svc exists (HTTP)" "$code" "200"
'

run "out-of-order/04-stale-observation-after-removal" "$FIXTURES/out-of-order/04-stale-observation-after-removal.json" '
  # Under last-write-wins-by-timestamp the stale observation (ts < tombstone ts)
  # must be rejected. Under multi-consumer reordering the removed event can also
  # land before either observed event, in which case node a is never materialized
  # and reachable/a returns 404 — both outcomes mean the edge is absent.
  raw=$(curl -sS "$BASE/api/v1/graph/reachable/a")
  echo "  reachable(a) raw: $raw"
  has_b=$(echo "$raw" | jq -r "if .reachable then ([.reachable[].id] | any(.==\"b\")) else false end")
  check "ooo-04 (LWW policy) edge a->b absent" "$has_b" "false"
'

# --------------------------- HEALTH ---------------------------

run "health/01-known-p95" "$FIXTURES/health/01-known-p95.json" '
  h=$(health s 86400)
  echo "  health(s): $h"
  sc=$(echo "$h" | jq -r .sampleCount)
  er=$(echo "$h" | jq -r .errorRate)
  p95=$(echo "$h" | jq -r .p95LatencyMs)
  check "h-01 sample_count==20" "$sc" "20"
  check "h-01 error_rate==0"    "$er" "0.0"
  check "h-01 p95==19"          "$p95" "19"
'

run "health/02-mixed-statuses" "$FIXTURES/health/02-mixed-statuses.json" '
  h=$(health s 86400)
  echo "  health(s): $h"
  sc=$(echo "$h" | jq -r .sampleCount)
  er=$(echo "$h" | jq -r .errorRate)
  p95=$(echo "$h" | jq -r .p95LatencyMs)
  check "h-02 sample_count==10" "$sc" "10"
  check "h-02 error_rate==0.3"  "$er" "0.3"
  check "h-02 p95==100"         "$p95" "100"
'

run "health/03-window-boundary" "$FIXTURES/health/03-window-boundary.json" '
  h=$(health s 86400)
  echo "  health(s, wide): $h"
  sc=$(echo "$h" | jq -r .sampleCount)
  check "h-03 wide-window sample_count==5" "$sc" "5"
'

run "health/04-no-in-window-samples" "$FIXTURES/health/04-no-in-window-samples.json" '
  h=$(health s 60)
  echo "  health(s, 60s): $h"
  sc=$(echo "$h" | jq -r .sampleCount)
  check "h-04 sample_count==0 (60s window)" "$sc" "0"
'

# --------------------------- NEW FIXTURES ---------------------------

run "topology/13-tied-shortest-paths" "$FIXTURES/topology/13-tied-shortest-paths.json" '
  spr=$(sp a d)
  echo "  sp(a,d): $spr"
  found=$(echo "$spr" | jq -r .found)
  weight=$(echo "$spr" | jq -r .totalLatencyMs)
  path=$(echo "$spr" | jq -c .path)
  check "13 sp(a,d).found==true"   "$found"  "true"
  check "13 sp(a,d).weight==20.0"  "$weight" "20.0"
  # Two ties: [a,b,d] or [a,c,d]. Either is acceptable; assert determinism by
  # re-querying and comparing.
  if [[ "$path" == "[\"a\",\"b\",\"d\"]" || "$path" == "[\"a\",\"c\",\"d\"]" ]]; then
    echo "  PASS 13 sp(a,d).path is one of the two ties ($path)"; PASS+=("13 sp(a,d).path-tie")
  else
    echo "  FAIL 13 sp(a,d).path expected [a,b,d] or [a,c,d], got $path"; FAIL+=("13 sp(a,d).path-tie")
  fi
  path2=$(sp a d | jq -c .path)
  check "13 sp(a,d) deterministic"  "$path2" "$path"
'

run "topology/14-tied-criticality" "$FIXTURES/topology/14-tied-criticality.json" '
  c=$(crit 2)
  echo "  crit(k=2): $c"
  ids=$(echo "$c" | jq -c "[.services[].id]")
  scores=$(echo "$c" | jq -c "[.services[].score]")
  check "14 crit(k=2).ids==[alpha,beta]" "$ids"    "[\"alpha\",\"beta\"]"
  check "14 crit(k=2).scores==[4.0,4.0]" "$scores" "[4.0,4.0]"
'

run "metadata/01-incremental-merge" "$FIXTURES/metadata/01-incremental-merge.json" '
  # No metadata-read endpoint exists; assert at minimum that the node materialised
  # and survives all three partial-attribute events without erroring.
  code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/v1/graph/reachable/merging-svc")
  check "md-01 merging-svc materialised (HTTP)" "$code" "200"
  reach=$(reach_ids merging-svc)
  check "md-01 merging-svc has no edges"  "$reach" "[]"
'

# Negative fixtures: we POST to /events/batch and expect HTTP 400 with a
# structured ApiError body (no stack trace). We deliberately tolerate the
# `02-missing-required-field` case accepting (Jackson defaults latency_ms to 0)
# because the README explicitly allows either rejection or acceptance there.

run "negative/01-malformed-json" "$FIXTURES/negative/01-malformed-json.json" '
  code=$(post_negative "'"$FIXTURES"'/negative/01-malformed-json.json")
  body=$(cat /tmp/sda_neg_body)
  echo "  HTTP $code body: $body"
  err=$(echo "$body" | jq -r .error 2>/dev/null || echo nope)
  check "neg-01 HTTP==400"               "$code" "400"
  check "neg-01 error==invalid_request"  "$err"  "invalid_request"
'

run "negative/02-missing-required-field" "$FIXTURES/negative/02-missing-required-field.json" '
  code=$(post_negative "'"$FIXTURES"'/negative/02-missing-required-field.json")
  body=$(cat /tmp/sda_neg_body)
  echo "  HTTP $code body: $body"
  # README: rejection or acceptance are both fine; but the response must never
  # be a 5xx with a stack trace.
  if [[ "$code" == "400" || "$code" == "202" ]]; then
    echo "  PASS neg-02 HTTP $code (either accept-with-default or 400 ok)"; PASS+=("neg-02 status")
  else
    echo "  FAIL neg-02 unexpected HTTP $code"; FAIL+=("neg-02 status")
  fi
'

run "negative/03-bad-status-enum" "$FIXTURES/negative/03-bad-status-enum.json" '
  code=$(post_negative "'"$FIXTURES"'/negative/03-bad-status-enum.json")
  body=$(cat /tmp/sda_neg_body)
  echo "  HTTP $code body: $body"
  err=$(echo "$body" | jq -r .error 2>/dev/null || echo nope)
  check "neg-03 HTTP==400"               "$code" "400"
  check "neg-03 error==invalid_request"  "$err"  "invalid_request"
'

run "negative/04-unknown-event-type" "$FIXTURES/negative/04-unknown-event-type.json" '
  code=$(post_negative "'"$FIXTURES"'/negative/04-unknown-event-type.json")
  body=$(cat /tmp/sda_neg_body)
  echo "  HTTP $code body: $body"
  err=$(echo "$body" | jq -r .error 2>/dev/null || echo nope)
  check "neg-04 HTTP==400"               "$code" "400"
  check "neg-04 error==invalid_request"  "$err"  "invalid_request"
'

run "negative/05-bad-timestamp" "$FIXTURES/negative/05-bad-timestamp.json" '
  code=$(post_negative "'"$FIXTURES"'/negative/05-bad-timestamp.json")
  body=$(cat /tmp/sda_neg_body)
  echo "  HTTP $code body: $body"
  err=$(echo "$body" | jq -r .error 2>/dev/null || echo nope)
  check "neg-05 HTTP==400"               "$code" "400"
  check "neg-05 error==invalid_request"  "$err"  "invalid_request"
'

echo
echo "================ SUMMARY ================"
echo "Passed: ${#PASS[@]}"
echo "Failed: ${#FAIL[@]}"
if (( ${#FAIL[@]} > 0 )); then
  echo "Failures:"
  for f in "${FAIL[@]}"; do echo "  - $f"; done
  exit 1
fi
exit 0
