#!/usr/bin/env bash
# Run once after the broker is up: creates the compacted master-data topic and lets the backend write to it (and nothing else).
#   ./bootstrap-topic.sh KAFKA_HOME DATA_DIR [TOPIC] [REPLICATION_FACTOR]
set -euo pipefail
[ $# -ge 2 ] || { sed -n '2,4p' "$0"; exit 1; }
KAFKA_HOME="$1"; DATA="$2"; TOPIC="${3:-fuelmarking.master-data}"; RF="${4:-1}"
"$KAFKA_HOME/bin/kafka-topics.sh" --command-config "$DATA/admin.properties" --bootstrap-server "$(sed -n 's/^bootstrap.servers=//p' "$DATA/admin.properties")" \
  --create --if-not-exists --topic "$TOPIC" --partitions 1 --replication-factor "$RF" --config cleanup.policy=compact
"$KAFKA_HOME/bin/kafka-acls.sh" --command-config "$DATA/admin.properties" --bootstrap-server "$(sed -n 's/^bootstrap.servers=//p' "$DATA/admin.properties")" \
  --add --allow-principal User:backend --operation Write --operation Describe --topic "$TOPIC"
