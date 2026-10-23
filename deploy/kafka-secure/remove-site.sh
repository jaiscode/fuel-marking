#!/usr/bin/env bash
# Revokes a marking site's Kafka access: deletes its login and every permission it was given.
# Use when a site is decommissioned or a site machine (and with it the settings file) is lost or stolen.
#   ./remove-site.sh KAFKA_HOME DATA_DIR SITE_ID [TOPIC]
# A connection that is already open keeps working until it reconnects (at most a few minutes); new logins are refused at once.
# To bring a site back, or to rotate its password instead, run add-site.sh again.
set -euo pipefail
[ $# -ge 3 ] || { sed -n '2,8p' "$0"; exit 1; }
KAFKA_HOME="$1"; DATA="$2"; SITE="$3"; TOPIC="${4:-fuelmarking.master-data}"
BOOT="$(sed -n 's/^bootstrap.servers=//p' "$DATA/admin.properties")"; USER="site-$SITE"
"$KAFKA_HOME/bin/kafka-configs.sh" --bootstrap-server "$BOOT" --command-config "$DATA/admin.properties" --alter \
  --delete-config "SCRAM-SHA-512" --entity-type users --entity-name "$USER" >/dev/null
"$KAFKA_HOME/bin/kafka-acls.sh" --bootstrap-server "$BOOT" --command-config "$DATA/admin.properties" --remove --force \
  --allow-principal "User:$USER" --operation Read --operation Describe --topic "$TOPIC" >/dev/null
"$KAFKA_HOME/bin/kafka-acls.sh" --bootstrap-server "$BOOT" --command-config "$DATA/admin.properties" --remove --force \
  --allow-principal "User:$USER" --operation Read --group "fuelmarking-site-$SITE" >/dev/null
rm -f "$DATA/sites/$SITE.env" "$DATA/sites/$SITE.yml"
echo "Site $SITE no longer has Kafka access (login $USER deleted, permissions removed)."
