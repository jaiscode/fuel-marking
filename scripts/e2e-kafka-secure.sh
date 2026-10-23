#!/usr/bin/env bash
# Verifies the secured Kafka set-up (deploy/kafka-secure) with the REAL backend and desktop code:
#   TLS + SCRAM logins + deny-by-default permissions, using the shipped scripts themselves.
#     KAFKA_HOME=/path/to/kafka_2.13-3.8.0  ./scripts/e2e-kafka-secure.sh
# Needs: Java 21, Maven, Node 20, PostgreSQL (PG_*), openssl, keytool, and an unpacked Apache Kafka 3.5+.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${KAFKA_HOME:?set KAFKA_HOME to an unpacked Apache Kafka (the folder containing bin/kafka-server-start.sh)}"
[ -x "$KAFKA_HOME/bin/kafka-server-start.sh" ] || { echo "no bin/kafka-server-start.sh in $KAFKA_HOME" >&2; exit 1; }
PG_BIN="${PG_BIN:-/opt/homebrew/opt/postgresql@16/bin}"; PG_PORT="${PG_PORT:-55432}"; PG_USER="${PG_USER:-postgres}"
WORK="$(mktemp -d)"; DEPLOY="$ROOT/deploy/kafka-secure"; KPORT=19094; API=http://localhost:18080; TOPIC=fuelmarking.master-data
PIDS=()
say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '   ok  %s\n' "$*"; }
fail() { printf '\n!! FAILED: %s\n   logs in %s\n' "$*" "$WORK" >&2; exit 1; }
cleanup() {
  for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done
  for port in $KPORT 18080 18090; do for p in $(lsof -nP -tiTCP:$port -sTCP:LISTEN 2>/dev/null); do kill "$p" 2>/dev/null || true; done; done
  sleep 3
}
trap cleanup EXIT
for port in $KPORT 9093 18080 18090; do
  if lsof -nP -iTCP:$port -sTCP:LISTEN >/dev/null 2>&1; then fail "port $port is already in use - stop what is using it and run again"; fi
done
jget() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }
pw()   { sed -n 's/.*password="\([^"]*\)".*/\1/p' "$1" | head -1; }

say "Building"
(cd "$ROOT/backend" && mvn -q -DskipTests package && mvn -q test-compile && \
  mvn -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" -Dmdep.includeScope=test)
CP="$(cat "$WORK/cp.txt")"; (cd "$ROOT" && mvn -q test-compile && mvn -q -DskipTests package)

say "Setting up the secured broker with the shipped scripts"
"$DEPLOY/make-certs.sh" "$WORK/certs" localhost >/dev/null
"$DEPLOY/setup-broker.sh" "$KAFKA_HOME" "$WORK/data" "$WORK/certs" localhost $KPORT >/dev/null
"$KAFKA_HOME/bin/kafka-server-start.sh" "$WORK/data/server.properties" > "$WORK/broker.log" 2>&1 & PIDS+=("$!")
for _ in $(seq 1 90); do
  "$KAFKA_HOME/bin/kafka-broker-api-versions.sh" --bootstrap-server localhost:$KPORT --command-config "$WORK/data/admin.properties" >/dev/null 2>&1 && break; sleep 1
done
"$KAFKA_HOME/bin/kafka-broker-api-versions.sh" --bootstrap-server localhost:$KPORT --command-config "$WORK/data/admin.properties" >/dev/null 2>&1 || fail "the broker did not start"
ok "broker up on TLS + SCRAM (accounts: admin, backend)"
"$DEPLOY/bootstrap-topic.sh" "$KAFKA_HOME" "$WORK/data" "$TOPIC" >/dev/null 2>&1
ok "compacted topic created; the backend may write it and nothing else"

say "Identity provider for the admin API, and the backend with a WRONG Kafka password"
cat > "$WORK/idp.json" <<'JSON'
{"interactiveLogin": true, "tokenCallbacks": [{"issuerId": "default", "tokenExpiry": 3600, "requestMappings": [
  {"requestParam": "grant_type", "match": "client_credentials",
   "claims": {"sub": "ci-admin", "name": "CI Admin", "aud": ["default"], "roles": ["fuelmarking-admin"]}}]}]}
JSON
SERVER_PORT=18090 JSON_CONFIG="$(cat "$WORK/idp.json")" java -cp "$CP" no.nav.security.mock.oauth2.StandaloneMockOAuth2ServerKt > "$WORK/idp.log" 2>&1 & PIDS+=("$!")
sleep 6
"$PG_BIN/dropdb" -h localhost -p "$PG_PORT" -U "$PG_USER" --if-exists fuel_central; "$PG_BIN/createdb" -h localhost -p "$PG_PORT" -U "$PG_USER" fuel_central
sed -E 's/(username="backend" password=")[^"]+/\1this-is-the-wrong-password/' "$WORK/data/backend.env" > "$WORK/backend-wrong.env"
start_backend() {  # start_backend ENVFILE
  ( set -a; . "$1"; set +a
    exec env OIDC_ISSUER_URI=http://localhost:18090/default OIDC_AUDIENCE=default \
      CENTRAL_DB_URL="jdbc:postgresql://localhost:$PG_PORT/fuel_central" CENTRAL_DB_USER="$PG_USER" CENTRAL_DB_PASSWORD=x \
      java -jar "$ROOT/backend/target/fuel-marking-central-1.0-SNAPSHOT.jar" --server.port=18080 ) > "$WORK/backend.log" 2>&1 &
  BACKEND_PID=$!; PIDS+=("$BACKEND_PID")
  for _ in $(seq 1 90); do grep -q "Started CentralApplication" "$WORK/backend.log" && return 0; sleep 1; done
  fail "backend did not start"
}
start_backend "$WORK/backend-wrong.env"
TOKEN="$(curl -s -d grant_type=client_credentials -d client_id=ci -d client_secret=x http://localhost:18090/default/token | jget "['access_token']")"
api() {
  local m="$1" path="$2" body="${3:-}" out code
  if [ -n "$body" ]; then out="$(curl -s -w '\n%{http_code} %{time_total}' -X "$m" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body" "$API$path")"
  else out="$(curl -s -w '\n%{http_code} %{time_total}' -X "$m" -H "Authorization: Bearer $TOKEN" "$API$path")"; fi
  local meta="${out##*$'\n'}"; code="${meta%% *}"
  python3 -c "print(int(float('${meta##* }')*1000))" > "$WORK/last_ms"   # a file, because callers run api inside $(...) where variables do not survive
  [ "$code" -lt 400 ] || fail "$m $path -> HTTP $code: ${out%$'\n'*}"
  printf '%s' "${out%$'\n'*}"
}
N="$RANDOM"
SITE_A="$(api POST /api/admin/sites "{\"code\":\"SA$N\",\"name\":\"Secure Site A $N\",\"siteType\":\"FIXED\"}" | jget "['id']")"; T1="$(cat "$WORK/last_ms")"
SITE_B="$(api POST /api/admin/sites "{\"code\":\"SB$N\",\"name\":\"Secure Site B $N\",\"siteType\":\"FIXED\"}" | jget "['id']")"
api POST /api/admin/omcs "{\"code\":\"SO$N\",\"name\":\"Secure Oil $N\"}" >/dev/null
USER_ID="$(api POST /api/admin/users "{\"username\":\"su$N\",\"fullName\":\"Secure User\",\"role\":\"SUPERVISOR\",\"siteId\":\"$SITE_A\"}" | jget "['id']")"
api PUT "/api/admin/users/$USER_ID/pin" '{"pin":"246810"}' >/dev/null; T2="$(cat "$WORK/last_ms")"
[ "$T1" -lt 3000 ] && [ "$T2" -lt 3000 ] || fail "admin requests were slow while Kafka rejected the backend (${T1}/${T2} ms)"
ok "admin changes still answer quickly (${T1}/${T2} ms) although Kafka refuses the backend's login"
for _ in $(seq 1 40); do
  STATUS="$(api GET /api/admin/master-data/status)"; [ "$(printf '%s' "$STATUS" | jget "['pending']")" -ge 4 ] && [ "$(printf '%s' "$STATUS" | jget "['lastError']")" != "None" ] && break; sleep 1
done
printf '%s' "$STATUS" | jget "['lastError']" | grep -qi "authentication" || fail "the status does not say why delivery is stuck: $STATUS"
ok "the changes are queued safely and the admin status says why: $(printf '%s' "$STATUS" | jget "['lastError']" | cut -c1-110)"

say "Adding two marking sites to Kafka (read-only accounts, own consumer group)"
"$DEPLOY/add-site.sh" "$KAFKA_HOME" "$WORK/data" "$WORK/certs" "$SITE_A" >/dev/null
"$DEPLOY/add-site.sh" "$KAFKA_HOME" "$WORK/data" "$WORK/certs" "$SITE_B" >/dev/null
ok "accounts site-$SITE_A and site-$SITE_B created with their own settings files"

say "The backend gets its correct password: the backlog is delivered, in order"
kill "$BACKEND_PID" 2>/dev/null || true; sleep 4
start_backend "$WORK/data/backend.env"
for _ in $(seq 1 60); do [ "$(api GET /api/admin/master-data/status | jget "['pending']")" = 0 ] && break; sleep 1; done
[ "$(api GET /api/admin/master-data/status | jget "['pending']")" = 0 ] || fail "the backlog was not delivered after the password was fixed"
ok "everything queued while Kafka refused the login was delivered once it was fixed"

say "A desktop installation connects with only its own settings file (environment variables)"
"$PG_BIN/dropdb" -h localhost -p "$PG_PORT" -U "$PG_USER" --if-exists fuel_e2e_sec; "$PG_BIN/createdb" -h localhost -p "$PG_PORT" -U "$PG_USER" fuel_e2e_sec
( set -a; . "$WORK/data/sites/$SITE_A.env"; set +a
  cd "$ROOT" && mvn -q test -Dtest=KafkaE2EStep -DargLine="-De2e.kafka.step=initial -De2e.kafka.bootstrap=localhost:$KPORT -De2e.kafka.topic=$TOPIC \
    -De2e.kafka.group=$SITE_A -De2e.kafka.site=$SITE_A -De2e.kafka.user=$USER_ID -De2e.kafka.name='Secure User' -De2e.kafka.pin=246810 \
    -Ddatabase.url=jdbc:postgresql://localhost:$PG_PORT/fuel_e2e_sec -Ddatabase.username=$PG_USER -Ddatabase.password=x" > "$WORK/desktop.log" 2>&1 ) \
  || { grep -E 'ERROR\]  |expected|did not' "$WORK/desktop.log" | head -5; fail "the desktop could not read master data through TLS + SCRAM"; }
grep -q 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' "$ROOT/target/surefire-reports/org.jai.fuelmarking.KafkaE2EStep.txt" || fail "desktop step did not run"
ok "site A's desktop read the site, company and user over TLS + SCRAM and the person can sign in with the PIN set in the web app"

say "The same desktop, configured ONLY by a settings file next to the jar (the Windows-friendly route: no environment variables)"
"$PG_BIN/dropdb" -h localhost -p "$PG_PORT" -U "$PG_USER" --if-exists fuel_e2e_sec2; "$PG_BIN/createdb" -h localhost -p "$PG_PORT" -U "$PG_USER" fuel_e2e_sec2
mkdir -p "$WORK/site folder"; cp "$WORK/data/sites/$SITE_A.yml" "$WORK/site folder/application-local.yml"
( cd "$ROOT" && env -u SYNC_KAFKA_PROPERTIES_SECURITY_PROTOCOL -u SYNC_KAFKA_PROPERTIES_SASL_JAAS_CONFIG \
  mvn -q test -Dtest=KafkaE2EStep -DargLine="-Dfuelmarking.config='$WORK/site folder/application-local.yml' -De2e.kafka.step=initial -De2e.kafka.bootstrap=localhost:$KPORT -De2e.kafka.topic=$TOPIC \
    -De2e.kafka.group=$SITE_A -De2e.kafka.site=$SITE_A -De2e.kafka.user=$USER_ID -De2e.kafka.name='Secure User' -De2e.kafka.pin=246810 \
    -Ddatabase.url=jdbc:postgresql://localhost:$PG_PORT/fuel_e2e_sec2 -Ddatabase.username=$PG_USER -Ddatabase.password=x" > "$WORK/desktop-yml.log" 2>&1 ) \
  || { grep -E 'ERROR\]  |expected|did not' "$WORK/desktop-yml.log" | head -5; fail "the desktop could not read master data using only a settings file"; }
grep -q 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' "$ROOT/target/surefire-reports/org.jai.fuelmarking.KafkaE2EStep.txt" || fail "desktop (settings file) step did not run"
ok "with no environment variables, only application-local.yml in a folder whose name has a space, the desktop connected over TLS + SCRAM"

say "The packaged jar in a folder with a space and an accent in its name, started from elsewhere, checks itself with --selfcheck"
API_KEY="$(api POST "/api/admin/sites/$SITE_A/api-key" | jget "['apiKey']")"
JARDIR="$WORK/Fuel Marking ü"; mkdir -p "$JARDIR"; cp "$ROOT/target/fuel-marking-desktop.jar" "$JARDIR/"
python3 - "$WORK/data/sites/$SITE_A.yml" "$JARDIR/application-local.yml" "$API_KEY" "$API" "$PG_PORT" "$PG_USER" <<'PY'
import sys
src, dst, key, base, port, user = sys.argv[1:]
t = open(src).read().replace("  enabled: true\n", f'  enabled: true\n  api-key: "{key}"\n  base-url: "{base}"\n', 1)
t += f'\ndatabase:\n  url: "jdbc:postgresql://localhost:{port}/fuel_e2e_sec2"\n  username: "{user}"\n  password: "x"\n'
open(dst, "w").write(t)
PY
set +e; ( cd "$WORK" && java -jar "$JARDIR/fuel-marking-desktop.jar" --selfcheck ) > "$WORK/selfcheck.out" 2>&1; RC=$?; set -e
sed 's/^/        /' "$WORK/selfcheck.out" | grep -E "^\s+\[" 
[ "$RC" = 0 ] || fail "--selfcheck found problems on a healthy installation"
for what in "\[ OK \] Java" "\[ OK \] Database" "\[ OK \] JavaFX" "\[ OK \] Central" "\[ OK \] Kafka" "Fuel Marking ü/application-local.yml"; do
  grep -Eq "$what" "$WORK/selfcheck.out" || fail "the self-check output lacks: $what"
done
if grep -qF "$API_KEY" "$WORK/selfcheck.out" || grep -qF "$(pw "$WORK/data/sites/$SITE_A.env")" "$WORK/selfcheck.out"; then fail "the self-check printed a secret"; fi
ok "found application-local.yml beside the jar, reached the database, started JavaFX, was accepted by the central system and read Kafka over TLS + SCRAM; no secret was printed"
sed -E 's/(username="site-[^"]+" password=")[^"]+/\1wrong-kafka-password/' "$JARDIR/application-local.yml" > "$JARDIR/broken.yml"
set +e; ( cd "$WORK" && java -Dfuelmarking.config="$JARDIR/broken.yml" -jar "$JARDIR/fuel-marking-desktop.jar" --selfcheck ) > "$WORK/selfcheck-bad.out" 2>&1; BAD=$?; set -e
[ "$BAD" = 1 ] || fail "a wrong Kafka password was not reported as a failure (exit $BAD)"
grep -Eq "\[FAIL\] Kafka.*uthentication" "$WORK/selfcheck-bad.out" || fail "the Kafka failure was not explained: $(grep FAIL "$WORK/selfcheck-bad.out")"
grep -q "wrong-kafka-password" "$WORK/selfcheck-bad.out" && fail "the self-check printed the wrong password"
ok "with a wrong Kafka password the self-check exits 1 and says: $(grep '\[FAIL\] Kafka' "$WORK/selfcheck-bad.out" | cut -c24-140)"

say "Revoking a site (a decommissioned or stolen site machine)"
SITE_C="removed-site-$N"
"$DEPLOY/add-site.sh" "$KAFKA_HOME" "$WORK/data" "$WORK/certs" "$SITE_C" >/dev/null
C_PW="$(pw "$WORK/data/sites/$SITE_C.env")"
[ -n "$C_PW" ] || fail "no password was generated for the site to revoke"
"$KAFKA_HOME/bin/kafka-acls.sh" --bootstrap-server localhost:$KPORT --command-config "$WORK/data/admin.properties" --list --principal "User:site-$SITE_C" 2>&1 | grep -q "site-$SITE_C" || fail "the account has no permissions before revocation (nothing to revoke?)"
"$DEPLOY/remove-site.sh" "$KAFKA_HOME" "$WORK/data" "$SITE_C" >/dev/null
[ ! -e "$WORK/data/sites/$SITE_C.env" ] || fail "the site's settings file was not removed"
if "$KAFKA_HOME/bin/kafka-acls.sh" --bootstrap-server localhost:$KPORT --command-config "$WORK/data/admin.properties" --list --principal "User:site-$SITE_C" 2>&1 | grep -q "principal=User:site-$SITE_C"; then fail "permissions remain after revocation"; fi
ok "the site's login and all its permissions were removed; its old settings file is now useless (checked below)"

say "Attacking the broker (wrong passwords, no TLS, plaintext, other sites' groups, writing, other topics, weak logins, a revoked site)"
SEC="-De2e.ksec.bootstrap=localhost:$KPORT -De2e.ksec.truststore=$WORK/certs/truststore.p12 -De2e.ksec.storepass=$(cat "$WORK/certs/store-password.txt")"
SEC="$SEC -De2e.ksec.admin.user=admin -De2e.ksec.admin.pass=$(cat "$WORK/data/credentials/admin.password")"
SEC="$SEC -De2e.ksec.backend.user=backend -De2e.ksec.backend.pass=$(cat "$WORK/data/credentials/backend.password")"
SEC="$SEC -De2e.ksec.a.id=$SITE_A -De2e.ksec.a.user=site-$SITE_A -De2e.ksec.a.pass=$(pw "$WORK/data/sites/$SITE_A.env")"
SEC="$SEC -De2e.ksec.removed.user=site-$SITE_C -De2e.ksec.removed.pass=$C_PW"
SEC="$SEC -De2e.ksec.b.id=$SITE_B -De2e.ksec.b.user=site-$SITE_B -De2e.ksec.b.pass=$(pw "$WORK/data/sites/$SITE_B.env")"
(cd "$ROOT" && mvn -q test -Dtest=KafkaSecurityStep -DargLine="$SEC" > "$WORK/security.log" 2>&1) \
  || { grep -E 'ERROR\]  |AssertionFailed|expected' "$WORK/security.log" | head -6; fail "the broker allowed something it should refuse"; }
grep -h 'Tests run' "$ROOT/target/surefire-reports/org.jai.fuelmarking.KafkaSecurityStep.txt" | sed 's/, Time.*//; s/^/   ok  /'
ok "every refusal held and the legitimate access still works"

printf '\n\033[1;32mSECURED KAFKA CHECKS PASSED\033[0m  (logs: %s)\n' "$WORK"
