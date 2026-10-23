#!/usr/bin/env bash
# Stops everything started by dev-up.sh. Data is kept; add --wipe to delete it as well (the downloaded distributions stay).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; DEV="$ROOT/.dev"; PG_BIN="${PG_BIN:-/opt/homebrew/opt/postgresql@16/bin}"
say() { printf '   %s\n' "$*"; }
stop_pid() { if [ -f "$DEV/pids/$1.pid" ]; then kill "$(cat "$DEV/pids/$1.pid")" 2>/dev/null && say "stopped $1"; rm -f "$DEV/pids/$1.pid"; fi; }
stop_port() { local pids; pids="$(lsof -nP -tiTCP:"$2" -sTCP:LISTEN 2>/dev/null)"; [ -n "$pids" ] && { kill $pids 2>/dev/null; say "stopped $1 (port $2)"; }; return 0; }
echo "Stopping the system..."
stop_pid desktop
for f in "$DEV"/pids/desktop-*.pid; do [ -e "$f" ] && stop_pid "$(basename "$f" .pid)"; done
pkill -f "$DEV/desktop/fuel-marking-desktop.jar" 2>/dev/null
stop_port "backend and web app" 8080
stop_port "Kafka" 19094
stop_port "Kafka controller" 9093
stop_port "Keycloak" 18081
if [ -f "$DEV/pgdata/PG_VERSION" ]; then "$PG_BIN/pg_ctl" -D "$DEV/pgdata" -m fast stop >/dev/null 2>&1 && say "stopped PostgreSQL"; fi
sleep 3
if [ "${1:-}" = "--wipe" ]; then
  find "$DEV" -mindepth 1 -maxdepth 1 ! -name dist -exec rm -rf {} +
  say "wiped $DEV (downloaded distributions kept in $DEV/dist)"
fi
echo "Done."
