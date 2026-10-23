#!/usr/bin/env bash
# Full-stack end-to-end check with a REAL Kafka broker, the real backend and real desktop code:
#   A. master data: admin API -> outbox -> Kafka -> desktop (real Kafka client) -> PIN sign-in,
#      including a broker outage (the broker process is frozen with SIGSTOP) and a late-joining desktop;
#   B. transfers: a warehouse desktop dispatches, the central system relays, a site desktop receives and
#      the receipt flows back - using per-site API keys issued through the admin API.
#
# Needs: a running PostgreSQL (see PG_*), Java 21, Maven. Everything else is started and stopped by this script.
# The broker is Kafka's own KRaft server run as a separate process (no Docker needed); data is temporary.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PG_BIN="${PG_BIN:-/opt/homebrew/opt/postgresql@16/bin}"
PG_PORT="${PG_PORT:-55432}"; PG_USER="${PG_USER:-postgres}"
WORK="$(mktemp -d)"
API=http://localhost:18080
TOPIC=fuelmarking.master-data
PIDS=()

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '   ok  %s\n' "$*"; }
fail() { printf '\n!! FAILED: %s\n' "$*" >&2; echo "   logs in $WORK" >&2; exit 1; }
cleanup() { for p in "${PIDS[@]:-}"; do kill -CONT "$p" 2>/dev/null || true; kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

psql_() { "$PG_BIN/psql" -h localhost -p "$PG_PORT" -U "$PG_USER" -q -t -A "$@"; }
resetdb() { "$PG_BIN/dropdb" -h localhost -p "$PG_PORT" -U "$PG_USER" --if-exists "$1"; "$PG_BIN/createdb" -h localhost -p "$PG_PORT" -U "$PG_USER" "$1"; }

say "Building"
(cd "$ROOT/web" && npm run build:embed >/dev/null)
(cd "$ROOT/backend" && mvn -q -DskipTests package && mvn -q test-compile && \
  mvn -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" -Dmdep.includeScope=test)
CP="$(cat "$WORK/cp.txt")"

say "Starting Kafka broker, identity provider and backend"
pkill -f StandaloneKafka 2>/dev/null || true; pkill -f StandaloneMockOAuth2Server 2>/dev/null || true; pkill -f fuel-marking-central 2>/dev/null || true
sleep 2
java -cp "$CP:$ROOT/backend/target/test-classes" org.jai.fuelmarking.central.StandaloneKafka > "$WORK/kafka.log" 2>&1 & KAFKA_PID=$!; PIDS+=("$KAFKA_PID")
for _ in $(seq 1 60); do grep -q KAFKA_READY "$WORK/kafka.log" && break; sleep 1; done
BOOTSTRAP="$(sed -n 's/^KAFKA_READY //p' "$WORK/kafka.log" | head -1)"; [ -n "$BOOTSTRAP" ] || fail "Kafka did not start"
ok "Kafka broker at $BOOTSTRAP (pid $KAFKA_PID)"

cat > "$WORK/idp.json" <<'JSON'
{"interactiveLogin": true, "tokenCallbacks": [{"issuerId": "default", "tokenExpiry": 3600, "requestMappings": [
  {"requestParam": "grant_type", "match": "client_credentials",
   "claims": {"sub": "ci-admin", "name": "CI Admin", "aud": ["default"], "roles": ["fuelmarking-admin"]}}]}]}
JSON
SERVER_PORT=18090 JSON_CONFIG="$(cat "$WORK/idp.json")" java -cp "$CP" no.nav.security.mock.oauth2.StandaloneMockOAuth2ServerKt > "$WORK/idp.log" 2>&1 & PIDS+=("$!")
sleep 6

resetdb fuel_central
OIDC_ISSUER_URI=http://localhost:18090/default OIDC_AUDIENCE=default KAFKA_BOOTSTRAP_SERVERS="$BOOTSTRAP" \
  CENTRAL_DB_URL="jdbc:postgresql://localhost:$PG_PORT/fuel_central" CENTRAL_DB_USER="$PG_USER" CENTRAL_DB_PASSWORD=x \
  java -jar "$ROOT/backend/target/fuel-marking-central-1.0-SNAPSHOT.jar" --server.port=18080 > "$WORK/backend.log" 2>&1 & PIDS+=("$!")
for _ in $(seq 1 60); do grep -q "Started CentralApplication" "$WORK/backend.log" && break; sleep 1; done
grep -q "Started CentralApplication" "$WORK/backend.log" || fail "backend did not start"
ok "backend started with Kafka at $BOOTSTRAP"

TOKEN="$(curl -s -d grant_type=client_credentials -d client_id=ci -d client_secret=x http://localhost:18090/default/token | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')"
# api METHOD PATH [JSON] -> prints body; sets LAST_MS to the request time in milliseconds
api() {
  local m="$1" path="$2" body="${3:-}" out
  if [ -n "$body" ]; then out="$(curl -s -w '\n%{http_code} %{time_total}' -X "$m" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body" "$API$path")"
  else out="$(curl -s -w '\n%{http_code} %{time_total}' -X "$m" -H "Authorization: Bearer $TOKEN" "$API$path")"; fi
  local meta="${out##*$'\n'}"; LAST_CODE="${meta%% *}"; LAST_MS="$(python3 -c "print(int(float('${meta##* }')*1000))")"
  printf '%s' "${out%$'\n'*}"
  case "$LAST_CODE" in 2??) ;; *) fail "$m $path -> HTTP $LAST_CODE: ${out%$'\n'*}";; esac
}
jget() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }
pending() { api GET /api/admin/master-data/status | jget "['pending']"; }
wait_drained() {   # wait_drained SECONDS
  for _ in $(seq 1 "$1"); do [ "$(pending)" = "0" ] && return 0; sleep 1; done; fail "outbox still has $(pending) pending message(s)"
}
desktop() {        # desktop STEP DB GROUP NAME PIN [OLDPIN] [EXPECT]
  local step="$1" db="$2" group="$3" name="$4" pin="$5" old="${6:-}" expect="${7:-}" extra=""
  [ -n "$old" ] && extra="$extra -De2e.kafka.oldpin=$old"; [ -n "$expect" ] && extra="$extra -De2e.kafka.expect=$expect"
  (cd "$ROOT" && mvn -q test -Dtest=KafkaE2EStep -DargLine="-De2e.kafka.step=$step -De2e.kafka.bootstrap=$BOOTSTRAP -De2e.kafka.topic=$TOPIC \
    -De2e.kafka.group=$group -De2e.kafka.site=$SITE -De2e.kafka.user=$USER_ID -De2e.kafka.name='$name' -De2e.kafka.pin=$pin -De2e.kafka.n=$N $extra \
    -Ddatabase.url=jdbc:postgresql://localhost:$PG_PORT/$db -Ddatabase.username=$PG_USER -Ddatabase.password=x" > "$WORK/desktop-$step.log" 2>&1) \
    || { grep -E 'ERROR\]  |expected|did not' "$WORK/desktop-$step.log" | head -5; fail "desktop step '$step'"; }
  grep -q 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' "$ROOT/target/surefire-reports/org.jai.fuelmarking.KafkaE2EStep.txt" || fail "desktop step '$step' did not run"
}

N="$RANDOM"
say "1. Administrator creates a site, a company and a user with a PIN"
SITE="$(api POST /api/admin/sites "{\"code\":\"KS$N\",\"name\":\"Kafka Site $N\",\"siteType\":\"FIXED\"}" | jget "['id']")"
api POST /api/admin/omcs "{\"code\":\"KO$N\",\"name\":\"Kafka Oil $N\"}" >/dev/null
USER_ID="$(api POST /api/admin/users "{\"username\":\"ku$N\",\"fullName\":\"Kafka User\",\"role\":\"SUPERVISOR\",\"siteId\":\"$SITE\"}" | jget "['id']")"
api PUT "/api/admin/users/$USER_ID/pin" '{"pin":"246810"}' >/dev/null
# the lists the sites choose from, and a jerrican head office registers for a rum type
RUM_ID="$(api POST /api/admin/rum-types "{\"code\":\"RUM$N\",\"name\":\"Rum $N\"}" | jget "['id']")"
api POST /api/admin/fuel-types "{\"code\":\"JET$N\",\"name\":\"Jet fuel $N\",\"rumTypeId\":\"$RUM_ID\"}" >/dev/null     # every fuel is marked with one rum type
METAL_ID="$(api GET /api/admin/container-types | python3 -c "import json,sys; print(next(t['id'] for t in json.load(sys.stdin) if t['code']=='JERRICAN_METAL'))")"
api POST /api/admin/jerricans "{\"barcode\":\"KJ-$N\",\"containerTypeId\":\"$METAL_ID\",\"capacityG\":18000,\"rumTypeId\":\"$RUM_ID\"}" >/dev/null
wait_drained 30; ok "delivered to Kafka (outbox empty)"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" "$API/api/admin/sites/$SITE/api-key")" = 200 ] && ok "(site API key issuance still works)"

say "2. A desktop that has never connected receives it through the real Kafka client and the user can sign in"
resetdb fuel_e2e_k1
desktop initial fuel_e2e_k1 "A$N" "Kafka User" 246810
ok "site, company, user, fuel type, rum type and the registered jerrican arrived; PIN 246810 signs in; the warehouse receives the jerrican empty"

say "3. Kafka goes down (broker process frozen); the administrator keeps working"
kill -STOP "$KAFKA_PID"; sleep 1
api PUT "/api/admin/users/$USER_ID" "{\"username\":\"ku$N\",\"fullName\":\"Kafka User Renamed\",\"role\":\"SUPERVISOR\",\"siteId\":\"$SITE\",\"active\":true}" >/dev/null; T1=$LAST_MS
api PUT "/api/admin/users/$USER_ID/pin" '{"pin":"135790"}' >/dev/null; T2=$LAST_MS
api POST /api/admin/omcs "{\"code\":\"KP$N\",\"name\":\"Second Oil $N\"}" >/dev/null; T3=$LAST_MS
for t in $T1 $T2 $T3; do [ "$t" -lt 3000 ] || fail "admin request took ${t} ms during the outage"; done
ok "admin requests during the outage took ${T1}/${T2}/${T3} ms (not blocked by Kafka)"
for _ in $(seq 1 20); do [ "$(pending)" -ge 3 ] && break; sleep 1; done
[ "$(pending)" -ge 3 ] || fail "changes were not queued during the outage"
ok "$(pending) change(s) safely queued; status reports: $(api GET /api/admin/master-data/status)"

say "4. Kafka comes back: the backlog is delivered and the same desktop catches up"
kill -CONT "$KAFKA_PID"
wait_drained 90; ok "backlog delivered"
desktop catchup fuel_e2e_k1 "A$N" "Kafka User Renamed" 135790 246810
ok "renamed user, new PIN 135790 works, old PIN 246810 rejected"

say "5. Administrator deactivates the user"
api PUT "/api/admin/users/$USER_ID" "{\"username\":\"ku$N\",\"fullName\":\"Kafka User Renamed\",\"role\":\"SUPERVISOR\",\"siteId\":\"$SITE\",\"active\":false}" >/dev/null
wait_drained 30
desktop inactive fuel_e2e_k1 "A$N" "Kafka User Renamed" 135790 "" inactive
ok "deactivated user can no longer sign in, even with the right PIN"

say "6. A brand-new desktop joins later and replays the whole topic"
resetdb fuel_e2e_k2
desktop latejoin fuel_e2e_k2 "B$N" "Kafka User Renamed" 135790 "" inactive
ok "late joiner converged to the current state (deactivated user, latest PIN)"
[ "$(psql_ fuel_e2e_k2 -c "select count(*) from oil_marketing_companies where code in ('KO$N','KP$N')")" = 2 ] || fail "late joiner is missing companies"
ok "late joiner has both companies"

say "7. Transfers between two sites, each with its own database and API key"
WH="$(api POST /api/admin/sites "{\"code\":\"TW$N\",\"name\":\"Transfer Warehouse $N\",\"siteType\":\"WAREHOUSE\"}" | jget "['id']")"
ST="$(api POST /api/admin/sites "{\"code\":\"TS$N\",\"name\":\"Transfer Site $N\",\"siteType\":\"FIXED\"}" | jget "['id']")"
KW="$(api POST "/api/admin/sites/$WH/api-key" | jget "['apiKey']")"; KS="$(api POST "/api/admin/sites/$ST/api-key" | jget "['apiKey']")"
resetdb fuel_e2e_wh; resetdb fuel_e2e_site
sync_step() {      # sync_step STEP DB KEY
  (cd "$ROOT" && mvn -q test -Dtest=E2EStep -DargLine="-De2e.step=$1 -De2e.url=$API -De2e.wh=$WH -De2e.site=$ST -De2e.key=$3 \
    -Ddatabase.url=jdbc:postgresql://localhost:$PG_PORT/$2 -Ddatabase.username=$PG_USER -Ddatabase.password=x" > "$WORK/sync-$1.log" 2>&1) \
    || { grep -E 'ERROR\]  |expected|should' "$WORK/sync-$1.log" | head -5; fail "sync step '$1'"; }
  grep -q 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' "$ROOT/target/surefire-reports/org.jai.fuelmarking.E2EStep.txt" || fail "sync step '$1' did not run"
}
sync_step warehouse fuel_e2e_wh "$KW";        ok "warehouse dispatched a container and pushed it"
sync_step site fuel_e2e_site "$KS";           ok "site pulled the transfer, received it (no weighing), pushed the receipt"
sync_step warehouse-final fuel_e2e_wh "$KW";  ok "warehouse sees the receipt and where the container is now"
[ "$(api GET "/api/web/transfers?siteId=$ST" | jget "['total']")" = 1 ] || fail "central web API does not show the transfer"
[ "$(api GET "/api/web/transfers?siteId=$ST" | jget "['items'][0]['received']")" = 1 ] || fail "central web API does not show the container as received"
ok "the web app's read API shows the transfer and that its container has been received"
[ "$(api GET "/api/web/stock-counts?siteId=$WH" | jget "['total']")" = 1 ] || fail "central web API does not show the warehouse stock count"
[ "$(api GET "/api/web/stock-counts?siteId=$WH" | jget "['items'][0]['totalVarianceG']")" = "-500.0" ] || fail "the stock count's difference did not arrive intact"
ok "the warehouse's stock count (0.5 kg short) reached head office and shows in the web API"

say "8. A weighed dose recorded at a site shows up correctly at head office"
resetdb fuel_e2e_dose
sync_step dose fuel_e2e_dose "$KS";            ok "site registered a truck, dosed 99.5 g (required 100 g, read from the scale) and synced"
MARKING="$(api GET "/api/web/markings?siteId=$ST" | jget "['items'][0]['id']")"
DETAIL="$(api GET "/api/web/markings/$MARKING")"
[ "$(printf '%s' "$DETAIL" | jget "['doseMeasuredG']")" = "99.5" ] || fail "central does not show the weighed dose: $DETAIL"
[ "$(printf '%s' "$DETAIL" | jget "['doseRequiredG']")" = "100.0" ] || fail "central does not show the required dose: $DETAIL"
[ "$(printf '%s' "$DETAIL" | jget "['doseSource']")" = "SCALE" ] || fail "central does not show the dose source: $DETAIL"
ok "web API: required 100.0 g, weighed 99.5 g, source SCALE"
[ "$(api GET "/api/web/overview?siteId=$ST" | jget "['rumMl']")" = "99.5" ] || fail "overview does not count the weighed dose"
ok "the overview counts the weighed 99.5 g, not the calculated 100 g"

printf '\n\033[1;32mALL FULL-STACK END-TO-END CHECKS PASSED\033[0m  (logs: %s)\n' "$WORK"
