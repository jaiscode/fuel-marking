#!/usr/bin/env bash
# Gives one marking site its own Kafka login that can ONLY read the master-data topic, with only its own consumer group.
#   ./add-site.sh KAFKA_HOME DATA_DIR CERT_DIR SITE_ID [TOPIC]
# SITE_ID is the site's id from the web app (the same value as sync.site-id on that desktop). Run again to rotate the password.
# Writes DATA_DIR/sites/SITE_ID.yml (an application-local.yml: easiest, especially on Windows) and SITE_ID.env (environment
# variables): the settings for that desktop. Hand one over privately.
set -euo pipefail
[ $# -ge 4 ] || { sed -n '2,6p' "$0"; exit 1; }
KAFKA_HOME="$1"; DATA="$2"; CERTS="$3"; SITE="$4"; TOPIC="${5:-fuelmarking.master-data}"
[[ "$SITE" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "SITE_ID may only contain letters, digits, '.', '_' and '-'" >&2; exit 1; }
BOOT="$(sed -n 's/^bootstrap.servers=//p' "$DATA/admin.properties")"
USER="site-$SITE"; PW="$(openssl rand -hex 20)"; umask 077; mkdir -p "$DATA/sites"
"$KAFKA_HOME/bin/kafka-configs.sh" --bootstrap-server "$BOOT" --command-config "$DATA/admin.properties" --alter \
  --add-config "SCRAM-SHA-512=[password=$PW]" --entity-type users --entity-name "$USER" >/dev/null
# read the topic, using exactly this group - not other sites' groups, no other topics, no writing
"$KAFKA_HOME/bin/kafka-acls.sh" --bootstrap-server "$BOOT" --command-config "$DATA/admin.properties" --add \
  --allow-principal "User:$USER" --operation Read --operation Describe --topic "$TOPIC" >/dev/null
"$KAFKA_HOME/bin/kafka-acls.sh" --bootstrap-server "$BOOT" --command-config "$DATA/admin.properties" --add \
  --allow-principal "User:$USER" --operation Read --group "fuelmarking-site-$SITE" >/dev/null
CERTS_ABS="$(cd "$CERTS" && pwd)"; STOREPASS="$(cat "$CERTS/store-password.txt")"
cat > "$DATA/sites/$SITE.env" <<ENV
# Settings for the desktop at site $SITE (environment variables). Copy truststore.p12 to the machine and fix the path.
# Shell / systemd format (values in single quotes): set -a; . file; set +a   or EnvironmentFile= in systemd. For Docker --env-file remove the quotes.
SYNC_KAFKA_BOOTSTRAP_SERVERS='$BOOT'
SYNC_KAFKA_PROPERTIES_SECURITY_PROTOCOL='SASL_SSL'
SYNC_KAFKA_PROPERTIES_SASL_MECHANISM='SCRAM-SHA-512'
SYNC_KAFKA_PROPERTIES_SASL_JAAS_CONFIG='org.apache.kafka.common.security.scram.ScramLoginModule required username="$USER" password="$PW";'
SYNC_KAFKA_PROPERTIES_SSL_TRUSTSTORE_TYPE='PKCS12'
SYNC_KAFKA_PROPERTIES_SSL_TRUSTSTORE_LOCATION='$CERTS_ABS/truststore.p12'
SYNC_KAFKA_PROPERTIES_SSL_TRUSTSTORE_PASSWORD='$STOREPASS'
ENV
# The same settings as an application-local.yml: no shell quoting, backslashes and spaces in paths are fine, so this is the
# easiest form for Windows desktops (put it next to fuel-marking-desktop.jar, rename it application-local.yml).
cat > "$DATA/sites/$SITE.yml" <<YML
# Settings for the desktop at site $SITE. Save as application-local.yml next to fuel-marking-desktop.jar.
# It holds a password: keep it private (on Windows, restrict the folder to the people who run the app).
# Copy truststore.p12 to the machine and set ssl.truststore.location to where you put it.
# Still to add from the web app: sync.api-key, and sync.base-url if it is not http://localhost:8080.
sync:
  enabled: true
  site-id: "$SITE"
  kafka:
    bootstrap-servers: "$BOOT"
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: SCRAM-SHA-512
      sasl.jaas.config: 'org.apache.kafka.common.security.scram.ScramLoginModule required username="$USER" password="$PW";'
      ssl.truststore.type: PKCS12
      ssl.truststore.location: '$CERTS_ABS/truststore.p12'
      ssl.truststore.password: '$STOREPASS'
YML
echo "Site $SITE can now read $TOPIC as $USER. Settings for its desktop: $DATA/sites/$SITE.yml (a settings file; or $SITE.env as environment variables)"
