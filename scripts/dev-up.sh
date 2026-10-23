#!/usr/bin/env bash
# Starts the WHOLE system on this machine for development and evaluation, wired together and seeded with sample data:
#   PostgreSQL (own instance) -> Keycloak (sign-in) -> secured Kafka (TLS + logins) -> backend + web app -> a desktop for one site.
#     ./scripts/dev-up.sh            start everything (first run also creates and seeds it; later runs reuse the data)
#     ./scripts/dev-down.sh          stop everything (keeps the data);   ./scripts/dev-down.sh --wipe   stop and forget it all
# Everything lives under .dev/ (data, logs, credentials). Needs: Java 21, Maven, Node 20, openssl, keytool, Homebrew PostgreSQL 16
# (PG_BIN), and Keycloak + Apache Kafka distributions: set KEYCLOAK_HOME / KAFKA_HOME, or they are downloaded (checksum-verified) to .dev/dist.
# DEVELOPMENT ONLY: fixed sample users, a setup client with a secret on disk, localhost addresses, plain-HTTP Keycloak.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"; DEV="$ROOT/.dev"
PG_BIN="${PG_BIN:-/opt/homebrew/opt/postgresql@16/bin}"
PG_PORT=55434; KC_PORT=18081; KAFKA_PORT=19094; APP_PORT=8080
KC_VERSION=26.8.1; KAFKA_VERSION=3.8.0
REALM="http://localhost:$KC_PORT/realms/fuelmarking"; APP="http://localhost:$APP_PORT"
mkdir -p "$DEV"/{pids,logs,state,dist,desktop}
say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()  { printf '   ok  %s\n' "$*"; }
die() { printf '\n!! %s\n' "$*" >&2; exit 1; }
listening() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
wait_for() { # wait_for SECONDS DESCRIPTION COMMAND...
  local n="$1" what="$2"; shift 2
  for _ in $(seq 1 "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  die "$what did not come up in ${n}s (see $DEV/logs)"
}
jget() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }

# ---------------------------------------------------------------- credentials (generated once)
if [ ! -f "$DEV/credentials.env" ]; then
  umask 077
  cat > "$DEV/credentials.env" <<CRED
KC_ADMIN_PW=$(openssl rand -hex 6)
ALICE_PW=$(openssl rand -hex 6)
VICTOR_PW=$(openssl rand -hex 6)
SETUP_SECRET=$(openssl rand -hex 16)
CRED
fi
# shellcheck disable=SC1091
. "$DEV/credentials.env"

# ---------------------------------------------------------------- distributions
ensure_keycloak() {
  if [ -n "${KEYCLOAK_HOME:-}" ]; then echo "$KEYCLOAK_HOME"; return; fi
  local dir="$DEV/dist/keycloak-$KC_VERSION"
  if [ ! -x "$dir/bin/kc.sh" ]; then
    echo "   downloading Keycloak $KC_VERSION (about 170 MB)..." >&2
    local zip="$DEV/dist/keycloak-$KC_VERSION.zip" base="https://github.com/keycloak/keycloak/releases/download/$KC_VERSION"
    curl -sSL -o "$zip" "$base/keycloak-$KC_VERSION.zip"; curl -sSL -o "$zip.sha1" "$base/keycloak-$KC_VERSION.zip.sha1"
    [ "$(cut -d' ' -f1 "$zip.sha1")" = "$(shasum -a 1 "$zip" | cut -d' ' -f1)" ] || die "Keycloak download failed its checksum"
    unzip -q "$zip" -d "$DEV/dist"; rm -f "$zip"
  fi
  echo "$dir"
}
ensure_kafka() {
  if [ -n "${KAFKA_HOME:-}" ]; then echo "$KAFKA_HOME"; return; fi
  local dir="$DEV/dist/kafka_2.13-$KAFKA_VERSION"
  if [ ! -x "$dir/bin/kafka-server-start.sh" ]; then
    echo "   downloading Apache Kafka $KAFKA_VERSION (about 115 MB)..." >&2
    local t="kafka_2.13-$KAFKA_VERSION.tgz" base="https://archive.apache.org/dist/kafka/$KAFKA_VERSION"
    curl -sSL -o "$DEV/dist/$t" "$base/$t"; curl -sSL -o "$DEV/dist/$t.sha512" "$base/$t.sha512"
    [ "$(tr -d ' \n' < "$DEV/dist/$t.sha512" | sed -E "s/^$t://" | tr 'A-F' 'a-f')" = "$(shasum -a 512 "$DEV/dist/$t" | cut -d' ' -f1)" ] || die "Kafka download failed its checksum"
    tar -xzf "$DEV/dist/$t" -C "$DEV/dist"; rm -f "$DEV/dist/$t"
  fi
  echo "$dir"
}
say "Preparing"
KC_DIST="$(ensure_keycloak)"; KAFKA_HOME="$(ensure_kafka)"
[ -x "$KC_DIST/bin/kc.sh" ] || die "no Keycloak at $KC_DIST"; [ -x "$KAFKA_HOME/bin/kafka-server-start.sh" ] || die "no Kafka at $KAFKA_HOME"
[ -x "$PG_BIN/pg_ctl" ] || die "PostgreSQL not found at $PG_BIN (set PG_BIN, e.g. brew install postgresql@16)"
if [ ! -f "$ROOT/backend/target/fuel-marking-central-1.0-SNAPSHOT.jar" ] || [ "${REBUILD:-0}" = 1 ]; then
  echo "   building the web app, backend and desktop jar..."
  (cd "$ROOT/web" && npm run build:embed >/dev/null) && (cd "$ROOT/backend" && mvn -q -DskipTests package) && (cd "$ROOT" && mvn -q -DskipTests package)
fi
ok "distributions and jars ready"

# ---------------------------------------------------------------- PostgreSQL
say "PostgreSQL (port $PG_PORT)"
if [ ! -f "$DEV/pgdata/PG_VERSION" ]; then "$PG_BIN/initdb" -D "$DEV/pgdata" -U postgres -A trust -E UTF8 --locale=en_US.UTF-8 >/dev/null; fi
if ! listening $PG_PORT; then
  "$PG_BIN/pg_ctl" -D "$DEV/pgdata" -o "-p $PG_PORT -k $DEV/state -c listen_addresses=127.0.0.1" -l "$DEV/logs/postgres.log" -w start >/dev/null
fi
for db in fuel_central fuel_marking_dev; do
  "$PG_BIN/psql" -h 127.0.0.1 -p $PG_PORT -U postgres -tAc "select 1 from pg_database where datname='$db'" | grep -q 1 \
    || "$PG_BIN/createdb" -h 127.0.0.1 -p $PG_PORT -U postgres "$db"
done
ok "databases fuel_central (head office) and fuel_marking_dev (the site's desktop)"

# ---------------------------------------------------------------- Keycloak
say "Keycloak (port $KC_PORT)"
if [ ! -d "$DEV/keycloak" ]; then cp -R "$KC_DIST" "$DEV/keycloak"; rm -rf "$DEV/keycloak/data"; mkdir -p "$DEV/keycloak/data/import"; cp "$ROOT/docs/keycloak/fuelmarking-realm.json" "$DEV/keycloak/data/import/"; fi
if ! listening $KC_PORT; then
  ( cd "$DEV/keycloak" && KC_BOOTSTRAP_ADMIN_USERNAME=admin KC_BOOTSTRAP_ADMIN_PASSWORD="$KC_ADMIN_PW" \
      nohup bin/kc.sh start-dev --http-port=$KC_PORT --hostname-strict=false --import-realm > "$DEV/logs/keycloak.log" 2>&1 & )
fi
wait_for 150 "Keycloak" curl -sf "$REALM/.well-known/openid-configuration"
ADMIN_TOKEN="$(curl -s -d client_id=admin-cli -d grant_type=password -d username=admin --data-urlencode "password=$KC_ADMIN_PW" "http://localhost:$KC_PORT/realms/master/protocol/openid-connect/token" | jget "['access_token']")" \
  || die "cannot sign in to Keycloak as admin (was .dev/credentials.env changed after the first run? use dev-down.sh --wipe)"
kc() { # kc METHOD PATH [JSON]
  local m="$1" p="$2" body="${3:-}" out code
  if [ -n "$body" ]; then out="$(curl -s -w '\n%{http_code}' -X "$m" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -d "$body" "http://localhost:$KC_PORT/admin/realms/fuelmarking$p")"
  else out="$(curl -s -w '\n%{http_code}' -X "$m" -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:$KC_PORT/admin/realms/fuelmarking$p")"; fi
  code="${out##*$'\n'}"; [ "$code" -lt 400 ] || die "Keycloak admin $m $p -> $code: ${out%$'\n'*}"
  printf '%s' "${out%$'\n'*}"
}
if [ ! -f "$DEV/state/keycloak-seeded" ]; then
  make_user() { # username First Last password role
    kc POST /users "{\"username\":\"$1\",\"email\":\"$1@example.org\",\"firstName\":\"$2\",\"lastName\":\"$3\",\"enabled\":true,\"emailVerified\":true,\"credentials\":[{\"type\":\"password\",\"value\":\"$4\",\"temporary\":false}]}" >/dev/null
    local id; id="$(kc GET "/users?username=$1&exact=true" | jget "[0]['id']")"
    kc POST "/users/$id/role-mappings/realm" "[$(kc GET "/roles/$5")]" >/dev/null
  }
  make_user alice Alice Admin "$ALICE_PW" fuelmarking-admin
  make_user victor Victor Viewer "$VICTOR_PW" fuelmarking-viewer
  # a client for THIS script to call the admin API (development only: delete it in a real deployment)
  kc POST /clients "{\"clientId\":\"fuelmarking-dev-setup\",\"name\":\"Dev setup (local only)\",\"enabled\":true,\"publicClient\":false,\"serviceAccountsEnabled\":true,\"standardFlowEnabled\":false,\"directAccessGrantsEnabled\":false,\"secret\":\"$SETUP_SECRET\",\"protocolMappers\":[{\"name\":\"audience\",\"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-audience-mapper\",\"config\":{\"included.custom.audience\":\"fuelmarking-api\",\"access.token.claim\":\"true\",\"id.token.claim\":\"false\"}},{\"name\":\"roles (flat)\",\"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-usermodel-realm-role-mapper\",\"config\":{\"claim.name\":\"roles\",\"multivalued\":\"true\",\"jsonType.label\":\"String\",\"access.token.claim\":\"true\",\"id.token.claim\":\"false\"}}]}" >/dev/null
  CID="$(kc GET "/clients?clientId=fuelmarking-dev-setup" | jget "[0]['id']")"
  SA="$(kc GET "/clients/$CID/service-account-user" | jget "['id']")"
  kc POST "/users/$SA/role-mappings/realm" "[$(kc GET /roles/fuelmarking-admin)]" >/dev/null
  touch "$DEV/state/keycloak-seeded"
fi
ok "realm 'fuelmarking' ready (users alice = administrator, victor = viewer)"

# ---------------------------------------------------------------- Kafka (secured)
say "Kafka with TLS + logins (port $KAFKA_PORT)"
KD="$DEV/kafka"
if [ ! -f "$KD/data/server.properties" ]; then
  mkdir -p "$KD"
  "$ROOT/deploy/kafka-secure/make-certs.sh" "$KD/certs" localhost >/dev/null
  "$ROOT/deploy/kafka-secure/setup-broker.sh" "$KAFKA_HOME" "$KD/data" "$KD/certs" localhost $KAFKA_PORT >/dev/null
fi
if ! listening $KAFKA_PORT; then nohup "$KAFKA_HOME/bin/kafka-server-start.sh" "$KD/data/server.properties" > "$DEV/logs/kafka.log" 2>&1 & fi
kafka_ready() { "$KAFKA_HOME/bin/kafka-broker-api-versions.sh" --bootstrap-server localhost:$KAFKA_PORT --command-config "$KD/data/admin.properties"; }
wait_for 120 "Kafka" kafka_ready
"$ROOT/deploy/kafka-secure/bootstrap-topic.sh" "$KAFKA_HOME" "$KD/data" >/dev/null 2>&1
ok "broker up; topic fuelmarking.master-data; the backend may write it, sites may only read it"

# ---------------------------------------------------------------- backend + web app
say "Backend and web app (port $APP_PORT)"
if ! listening $APP_PORT; then
  ( set -a; . "$KD/data/backend.env"; set +a
    OIDC_ISSUER_URI="$REALM" OIDC_AUDIENCE=fuelmarking-api \
    CENTRAL_DB_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/fuel_central" CENTRAL_DB_USER=postgres CENTRAL_DB_PASSWORD="" \
    nohup java -jar "$ROOT/backend/target/fuel-marking-central-1.0-SNAPSHOT.jar" --server.port=$APP_PORT > "$DEV/logs/backend.log" 2>&1 & )
fi
wait_for 120 "the backend" curl -sf "$APP/actuator/health"
ok "backend up; the web app is served by it"

# ---------------------------------------------------------------- seed head-office data through the admin API
say "Sample data (head office)"
SETUP_TOKEN="$(curl -s -d grant_type=client_credentials -d client_id=fuelmarking-dev-setup --data-urlencode "client_secret=$SETUP_SECRET" "http://localhost:$KC_PORT/realms/fuelmarking/protocol/openid-connect/token" | jget "['access_token']")" \
  || die "could not get a token for the setup client"
api() { # api METHOD PATH [JSON]
  local m="$1" p="$2" body="${3:-}" out code
  if [ -n "$body" ]; then out="$(curl -s -w '\n%{http_code}' -X "$m" -H "Authorization: Bearer $SETUP_TOKEN" -H 'Content-Type: application/json' -d "$body" "$APP$p")"
  else out="$(curl -s -w '\n%{http_code}' -X "$m" -H "Authorization: Bearer $SETUP_TOKEN" "$APP$p")"; fi
  code="${out##*$'\n'}"; [ "$code" -lt 400 ] || die "$m $p -> $code: ${out%$'\n'*}"
  printf '%s' "${out%$'\n'*}"
}
if [ ! -f "$DEV/state/central-seeded" ]; then
  SITE_ID="$(api POST /api/admin/sites '{"code":"KAMPALA","name":"Kampala Central","siteType":"FIXED"}' | jget "['id']")"
  api POST /api/admin/sites '{"code":"NAIROBI-WH","name":"Nairobi Warehouse","siteType":"WAREHOUSE"}' >/dev/null
  api POST /api/admin/omcs '{"code":"ALPHA","name":"Alpha Oil"}' >/dev/null
  api POST /api/admin/omcs '{"code":"BETA","name":"Beta Fuels"}' >/dev/null
  for u in "sam|Sam Supervisor|SUPERVISOR" "dana|Dana Dosing|DOSING_OPERATOR" "dev|Dev Dispense|DISPENSE_OPERATOR" "wendy|Wendy Warehouse|WAREHOUSE_MANAGER"; do
    IFS='|' read -r name full role <<<"$u"
    UID_="$(api POST /api/admin/users "{\"username\":\"$name\",\"fullName\":\"$full\",\"role\":\"$role\",\"siteId\":\"$SITE_ID\"}" | jget "['id']")"
    api PUT "/api/admin/users/$UID_/pin" '{"pin":"246810"}' >/dev/null
  done
  api POST "/api/admin/sites/$SITE_ID/api-key" | jget "['apiKey']" > "$DEV/state/api-key"; chmod 600 "$DEV/state/api-key"
  echo "$SITE_ID" > "$DEV/state/site-id"
  touch "$DEV/state/central-seeded"
fi
SITE_ID="$(cat "$DEV/state/site-id")"; API_KEY="$(cat "$DEV/state/api-key")"
for _ in $(seq 1 60); do [ "$(api GET /api/admin/master-data/status | jget "['pending']")" = 0 ] && break; sleep 1; done
[ "$(api GET /api/admin/master-data/status | jget "['pending']")" = 0 ] || die "master data is still waiting to reach Kafka (see $DEV/logs/backend.log)"
ok "site Kampala Central (id $SITE_ID), a warehouse, two oil companies and four people with PIN 246810; all delivered to Kafka"

# ---------------------------------------------------------------- the site's desktop
say "Desktop for site Kampala Central"
if [ ! -f "$DEV/state/kafka-site-added" ]; then
  "$ROOT/deploy/kafka-secure/add-site.sh" "$KAFKA_HOME" "$KD/data" "$KD/certs" "$SITE_ID" >/dev/null
  touch "$DEV/state/kafka-site-added"
fi
python3 - "$KD/data/sites/$SITE_ID.yml" "$DEV/desktop/application-local.yml" "$API_KEY" "$APP" "$PG_PORT" <<'PY'
import sys
src, dst, key, base, port = sys.argv[1:]
t = open(src).read().replace("  enabled: true\n", f'  enabled: true\n  api-key: "{key}"\n  base-url: "{base}"\n', 1)
t += f'\ndatabase:\n  url: "jdbc:postgresql://127.0.0.1:{port}/fuel_marking_dev"\n  username: "postgres"\n  password: ""\n'
open(dst, "w").write(t)
PY
chmod 600 "$DEV/desktop/application-local.yml"
cp "$ROOT/target/fuel-marking-desktop.jar" "$DEV/desktop/fuel-marking-desktop.jar"
( cd "$DEV/desktop" && java -jar fuel-marking-desktop.jar --selfcheck ) > "$DEV/logs/selfcheck.txt" 2>&1 || { cat "$DEV/logs/selfcheck.txt"; die "the desktop's self-check found problems"; }
ok "self-check passed: database, JavaFX, central system, Kafka over TLS + SCRAM"
if [ -f "$DEV/pids/desktop.pid" ] && kill -0 "$(cat "$DEV/pids/desktop.pid")" 2>/dev/null; then ok "the desktop app is already running"
else
  ( cd "$DEV/desktop" || exit 1; nohup java -jar fuel-marking-desktop.jar > "$DEV/logs/desktop.log" 2>&1 < /dev/null & echo $! > "$DEV/pids/desktop.pid" )
  sleep 8
  kill -0 "$(cat "$DEV/pids/desktop.pid")" 2>/dev/null || die "the desktop app exited at start-up (see $DEV/logs/desktop.log)"
  ok "the desktop app is running (its window, and the scale simulator window, are on your screen)"
fi
for _ in $(seq 1 60); do
  [ "$("$PG_BIN/psql" -h 127.0.0.1 -p $PG_PORT -U postgres -tAc "select count(*) from app_users" fuel_marking_dev 2>/dev/null || echo 0)" -ge 4 ] && break; sleep 1
done
ok "master data reached the desktop through Kafka: $("$PG_BIN/psql" -h 127.0.0.1 -p $PG_PORT -U postgres -tAc "select count(*)||' people, '||(select count(*) from marking_sites)||' sites, '||(select count(*) from oil_marketing_companies)||' oil companies' from app_users" fuel_marking_dev)"

# ---------------------------------------------------------------- more sites: each one is its OWN desktop installation
# A desktop syncs as exactly one site (its own API key and Kafka login), so a second site needs a second installation with
# its own database. List the site codes in .dev/extra-sites (one per line); they must already exist in the web app.
EXTRA_DESKTOPS=""
if [ -f "$DEV/extra-sites" ]; then
  while read -r CODE; do
    [ -n "$CODE" ] || continue
    say "Desktop for site $CODE"
    low="$(printf '%s' "$CODE" | tr 'A-Z-' 'a-z_')"; DB="fuel_marking_$low"; DIR="$DEV/desktop-$low"; mkdir -p "$DIR"
    XID="$(api GET /api/admin/sites | python3 -c "import json,sys; print(next((s['id'] for s in json.load(sys.stdin) if s['code']=='$CODE'), ''))")"
    [ -n "$XID" ] || die "site $CODE does not exist in the web app yet (Administration -> Sites)"
    if [ ! -f "$DEV/state/api-key-$low" ]; then api POST "/api/admin/sites/$XID/api-key" | jget "['apiKey']" > "$DEV/state/api-key-$low"; chmod 600 "$DEV/state/api-key-$low"; fi
    if [ ! -f "$DEV/state/kafka-site-$low" ]; then "$ROOT/deploy/kafka-secure/add-site.sh" "$KAFKA_HOME" "$KD/data" "$KD/certs" "$XID" >/dev/null; touch "$DEV/state/kafka-site-$low"; fi
    "$PG_BIN/psql" -h 127.0.0.1 -p $PG_PORT -U postgres -tAc "select 1 from pg_database where datname='$DB'" postgres | grep -q 1 \
      || "$PG_BIN/createdb" -h 127.0.0.1 -p $PG_PORT -U postgres "$DB"
    python3 - "$KD/data/sites/$XID.yml" "$DIR/application-local.yml" "$(cat "$DEV/state/api-key-$low")" "$APP" "$PG_PORT" "$DB" <<'PY'
import sys
src, dst, key, base, port, db = sys.argv[1:]
t = open(src).read().replace("  enabled: true\n", f'  enabled: true\n  api-key: "{key}"\n  base-url: "{base}"\n', 1)
t += f'\ndatabase:\n  url: "jdbc:postgresql://127.0.0.1:{port}/{db}"\n  username: "postgres"\n  password: ""\n'
open(dst, "w").write(t)
PY
    chmod 600 "$DIR/application-local.yml"
    cp "$ROOT/target/fuel-marking-desktop.jar" "$DIR/fuel-marking-desktop.jar"
    ( cd "$DIR" && java -jar fuel-marking-desktop.jar --selfcheck ) > "$DEV/logs/selfcheck-$low.txt" 2>&1 || { cat "$DEV/logs/selfcheck-$low.txt"; die "the self-check for $CODE found problems"; }
    ok "self-check passed for $CODE"
    if [ -f "$DEV/pids/desktop-$low.pid" ] && kill -0 "$(cat "$DEV/pids/desktop-$low.pid")" 2>/dev/null; then ok "the $CODE desktop is already running"
    else
      ( cd "$DIR" || exit 1; nohup java -jar fuel-marking-desktop.jar > "$DEV/logs/desktop-$low.log" 2>&1 < /dev/null & echo $! > "$DEV/pids/desktop-$low.pid" )
      sleep 8
      kill -0 "$(cat "$DEV/pids/desktop-$low.pid")" 2>/dev/null || die "the $CODE desktop exited at start-up (see $DEV/logs/desktop-$low.log)"
      ok "the $CODE desktop is running (its own window)"
    fi
    EXTRA_DESKTOPS="$EXTRA_DESKTOPS $CODE"
  done < "$DEV/extra-sites"
fi

cat <<DONE

$(printf '\033[1;32mEVERYTHING IS RUNNING\033[0m')

  Web app          $APP                (sign in through Keycloak)
      administrator   alice   / $ALICE_PW
      viewer          victor  / $VICTOR_PW
  Keycloak admin   http://localhost:$KC_PORT          admin / $KC_ADMIN_PW
  Desktop app      its window is open: pick "Kampala Central", then a person; PIN 246810 for all
      sam = supervisor, dana = dosing operator, dev = dispense operator, wendy = warehouse manager
      (a Scale simulator window is open beside it: place a weight, then press Read scale in the app)
  Kafka            localhost:$KAFKA_PORT (TLS + logins)      PostgreSQL   127.0.0.1:$PG_PORT (no password, local only)

  Logs: $DEV/logs        Logins are also in: $DEV/credentials.env
${EXTRA_DESKTOPS:+  More desktops    one window per site:$EXTRA_DESKTOPS (each syncs only as its own site)
}  Stop everything:  ./scripts/dev-down.sh        (add --wipe to forget all data)
DONE
