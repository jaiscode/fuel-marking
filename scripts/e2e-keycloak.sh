#!/usr/bin/env bash
# Verifies the web app's sign-in against a REAL Keycloak: browser login (authorization code + PKCE), roles, audience,
# silent token refresh, sign-out, and the API's refusals (wrong audience, viewer on admin endpoints, no role).
#
# Needs: Java 21, Maven, Node 20, Chrome, a running PostgreSQL (PG_*), and an unpacked Keycloak distribution:
#   KEYCLOAK_HOME=/path/to/keycloak-26.x  ./scripts/e2e-keycloak.sh
# Keycloak is run from a temporary COPY with an in-memory database, so your installation is never touched.
# The realm under test is docs/keycloak/fuelmarking-realm.json - the same file you would import for real.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${KEYCLOAK_HOME:?set KEYCLOAK_HOME to an unpacked Keycloak distribution (the folder containing bin/kc.sh)}"
[ -x "$KEYCLOAK_HOME/bin/kc.sh" ] || { echo "no bin/kc.sh in $KEYCLOAK_HOME" >&2; exit 1; }
PG_BIN="${PG_BIN:-/opt/homebrew/opt/postgresql@16/bin}"; PG_PORT="${PG_PORT:-55432}"; PG_USER="${PG_USER:-postgres}"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
WORK="$(mktemp -d)"; KC_PORT=18081; KC="http://localhost:$KC_PORT"; REALM="$KC/realms/fuelmarking"
APP_PORT=8080   # the realm file registers http://localhost:8080/ as a redirect URI
PIDS=()
say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '   ok  %s\n' "$*"; }
fail() { printf '\n!! FAILED: %s\n   logs in %s\n' "$*" "$WORK" >&2; exit 1; }
cleanup() {
  for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done
  # Keycloak's java process is not the script we started and its command line has only relative paths, so find it by port:
  # the port was verified free at start-up, so whatever listens there now is ours.
  for p in $(lsof -nP -tiTCP:$KC_PORT -sTCP:LISTEN 2>/dev/null); do kill "$p" 2>/dev/null || true; done
  sleep 3   # let them actually exit before the shell returns
}
trap cleanup EXIT

say "Building"
(cd "$ROOT/web" && npm run build:embed >/dev/null)
(cd "$ROOT/backend" && mvn -q -DskipTests package && mvn -q test-compile && \
  mvn -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" -Dmdep.includeScope=test)
CP="$(cat "$WORK/cp.txt")"
pkill -f StandaloneKafka 2>/dev/null || true; pkill -f fuel-marking-central 2>/dev/null || true; sleep 2
for port in $KC_PORT $APP_PORT 18082; do
  if lsof -nP -iTCP:$port -sTCP:LISTEN >/dev/null 2>&1; then fail "port $port is already in use (a leftover Keycloak or backend?) - stop it and run again"; fi
done

say "Starting Keycloak (temporary copy, in-memory database) with the realm file"
cp -R "$KEYCLOAK_HOME" "$WORK/kc"
rm -rf "$WORK/kc/data"   # never inherit a database, admin account or realms from the source folder
mkdir -p "$WORK/kc/data/import" && cp "$ROOT/docs/keycloak/fuelmarking-realm.json" "$WORK/kc/data/import/"
ADMIN_PW="admin-$RANDOM-$RANDOM"
( cd "$WORK/kc" && KC_BOOTSTRAP_ADMIN_USERNAME=admin KC_BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PW" \
    bin/kc.sh start-dev --http-port=$KC_PORT --hostname-strict=false --db=dev-mem --import-realm > "$WORK/keycloak.log" 2>&1 ) & PIDS+=("$!")
for _ in $(seq 1 120); do curl -sf "$REALM/.well-known/openid-configuration" >/dev/null 2>&1 && break; sleep 2; done
curl -sf "$REALM/.well-known/openid-configuration" >/dev/null || fail "Keycloak did not start"
ok "Keycloak is up; realm 'fuelmarking' imported"

jget() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }
ADMIN_TOKEN="$(curl -s -d client_id=admin-cli -d grant_type=password -d username=admin --data-urlencode "password=$ADMIN_PW" "$KC/realms/master/protocol/openid-connect/token" | jget "['access_token']")"
kc() { # kc METHOD PATH [JSON]  -> body; fails on HTTP >= 400
  local m="$1" p="$2" body="${3:-}" out code
  if [ -n "$body" ]; then out="$(curl -s -w '\n%{http_code}' -X "$m" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -d "$body" "$KC/admin/realms/fuelmarking$p")"
  else out="$(curl -s -w '\n%{http_code}' -X "$m" -H "Authorization: Bearer $ADMIN_TOKEN" "$KC/admin/realms/fuelmarking$p")"; fi
  code="${out##*$'\n'}"; [ "$code" -lt 400 ] || fail "Keycloak admin $m $p -> $code: ${out%$'\n'*}"
  printf '%s' "${out%$'\n'*}"
}
make_user() { # make_user username First Last password [role]
  local pw="$4"
  kc POST /users "{\"username\":\"$1\",\"email\":\"$1@example.org\",\"firstName\":\"$2\",\"lastName\":\"$3\",\"enabled\":true,\"emailVerified\":true,\"credentials\":[{\"type\":\"password\",\"value\":\"$pw\",\"temporary\":false}]}" >/dev/null
  local id; id="$(kc GET "/users?username=$1&exact=true" | jget "[0]['id']")"
  if [ -n "${5:-}" ]; then kc POST "/users/$id/role-mappings/realm" "[$(kc GET "/roles/$5")]" >/dev/null; fi
}
N="$RANDOM"
PW_A="pw-$RANDOM-a"; PW_V="pw-$RANDOM-v"; PW_N="pw-$RANDOM-n"
make_user "alice$N" Alice Admin  "$PW_A" fuelmarking-admin
make_user "victor$N" Victor Viewer "$PW_V" fuelmarking-viewer
make_user "nina$N" Nina Norole   "$PW_N"
# a 60 s access token so silent refresh can be seen without waiting long, and a second app for the wrong-audience check
CID="$(kc GET "/clients?clientId=fuelmarking-web" | jget "[0]['id']")"
kc PUT "/clients/$CID" "$(kc GET "/clients/$CID" | python3 -c "import json,sys; d=json.load(sys.stdin); d['attributes']['access.token.lifespan']='60'; print(json.dumps(d))")" >/dev/null
kc POST /clients '{"clientId":"other-app","publicClient":true,"directAccessGrantsEnabled":true,"standardFlowEnabled":false,"protocol":"openid-connect"}' >/dev/null
ok "users created (administrator, viewer, no role); client token lifetime set to 60 s; a second app 'other-app' registered"

say "Starting Kafka, and the backend twice: flat 'roles' claim (port $APP_PORT) and Keycloak's nested realm_access.roles (port 18082)"
java -cp "$CP:$ROOT/backend/target/test-classes" org.jai.fuelmarking.central.StandaloneKafka > "$WORK/kafka.log" 2>&1 & PIDS+=("$!")
for _ in $(seq 1 60); do grep -q KAFKA_READY "$WORK/kafka.log" && break; sleep 1; done
BOOTSTRAP="$(sed -n 's/^KAFKA_READY //p' "$WORK/kafka.log" | head -1)"; [ -n "$BOOTSTRAP" ] || fail "Kafka did not start"
for d in fuel_central; do "$PG_BIN/dropdb" -h localhost -p "$PG_PORT" -U "$PG_USER" --if-exists $d; "$PG_BIN/createdb" -h localhost -p "$PG_PORT" -U "$PG_USER" $d; done
backend() { # backend PORT ROLES_CLAIM EXTRA
  OIDC_ISSUER_URI="$REALM" OIDC_AUDIENCE=fuelmarking-api OIDC_ROLES_CLAIM="$2" KAFKA_BOOTSTRAP_SERVERS="$BOOTSTRAP" \
  CENTRAL_DB_URL="jdbc:postgresql://localhost:$PG_PORT/fuel_central" CENTRAL_DB_USER="$PG_USER" CENTRAL_DB_PASSWORD=x \
  java -jar "$ROOT/backend/target/fuel-marking-central-1.0-SNAPSHOT.jar" --server.port="$1" ${3:-} > "$WORK/backend-$1.log" 2>&1 & PIDS+=("$!")
}
backend $APP_PORT roles
for _ in $(seq 1 90); do grep -q "Started CentralApplication" "$WORK/backend-$APP_PORT.log" && break; sleep 1; done
grep -q "Started CentralApplication" "$WORK/backend-$APP_PORT.log" || fail "backend did not start"
backend 18082 realm_access.roles --fuelmarking.master-data-drain-enabled=false
for _ in $(seq 1 90); do grep -q "Started CentralApplication" "$WORK/backend-18082.log" && break; sleep 1; done
grep -q "Started CentralApplication" "$WORK/backend-18082.log" || fail "second backend did not start"
curl -s "http://localhost:$APP_PORT/api/public/auth-config" | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['issuer'].endswith('/realms/fuelmarking') and d['audience']=='fuelmarking-api', d; print('   ok  the backend publishes the realm as issuer and fuelmarking-api as audience')"

say "The browser walk-through (real Chrome, real Keycloak login page)"
export APP_URL="http://localhost:$APP_PORT" KC_URL="$KC" CHROME TOKENS_OUT="$WORK/tokens.json" REFRESH_WAIT_SECONDS=45
export USERS="{\"admin\":{\"username\":\"alice$N\",\"password\":\"$PW_A\"},\"viewer\":{\"username\":\"victor$N\",\"password\":\"$PW_V\"},\"norole\":{\"username\":\"nina$N\",\"password\":\"$PW_N\"}}"
(cd "$ROOT/web" && node e2e/keycloak-flow.mjs) || fail "browser flow"

say "API checks with tokens straight from Keycloak"
status() { curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $2" "$1"; }
ADMIN_JWT="$(python3 -c "import json; print(json.load(open('$WORK/tokens.json'))['admin'])")"
VIEWER_JWT="$(python3 -c "import json; print(json.load(open('$WORK/tokens.json'))['viewer'])")"
OTHER_JWT="$(curl -s -d grant_type=password -d client_id=other-app -d "username=alice$N" --data-urlencode "password=$PW_A" "$REALM/protocol/openid-connect/token" | jget "['access_token']")"
for port in $APP_PORT 18082; do
  style="flat roles claim"; [ "$port" = 18082 ] && style="nested realm_access.roles"
  [ "$(status "http://localhost:$port/api/admin/users" "$ADMIN_JWT")" = 200 ]  || fail "admin token refused on admin API ($style)"
  [ "$(status "http://localhost:$port/api/web/overview" "$VIEWER_JWT")" = 200 ] || fail "viewer token refused on reports ($style)"
  [ "$(status "http://localhost:$port/api/admin/users" "$VIEWER_JWT")" = 403 ] || fail "viewer token allowed on admin API ($style)"
  [ "$(status "http://localhost:$port/api/web/overview" "$OTHER_JWT")" = 401 ]  || fail "a valid Keycloak token meant for another app was accepted ($style)"
  [ "$(status "http://localhost:$port/api/web/overview" "")" = 401 ]            || fail "no token was accepted ($style)"
  ok "backend reading the $style: admin 200, viewer reads 200 / admin 403, another app's token 401, no token 401"
done
printf '\n\033[1;32mKEYCLOAK SIGN-IN CHECKS PASSED\033[0m  (logs: %s)\n' "$WORK"
