#!/usr/bin/env bash
# Prepares a single-node, secured Kafka broker (KRaft): TLS everywhere, SCRAM logins, deny-by-default permissions.
#   ./setup-broker.sh KAFKA_HOME DATA_DIR CERT_DIR ADVERTISED_HOST [PORT]
# KAFKA_HOME   an unpacked Apache Kafka (3.5 or newer)          DATA_DIR  where config, logs and credentials go
# CERT_DIR     the output of make-certs.sh                       ADVERTISED_HOST  the name sites use to reach the broker
# Creates two accounts, "admin" (you, for administration) and "backend" (the central backend, which only writes),
# writes DATA_DIR/server.properties and formats the storage. Then:   KAFKA_HOME/bin/kafka-server-start.sh DATA_DIR/server.properties
# Afterwards run ./bootstrap-topic.sh once, and ./add-site.sh for each marking site.
set -euo pipefail
[ $# -ge 4 ] || { sed -n '2,10p' "$0"; exit 1; }
KAFKA_HOME="$1"; DATA="$2"; CERTS="$3"; HOST="$4"; PORT="${5:-9094}"
[ -x "$KAFKA_HOME/bin/kafka-storage.sh" ] || { echo "no bin/kafka-storage.sh in $KAFKA_HOME" >&2; exit 1; }
[ -f "$CERTS/broker.p12" ] && [ -f "$CERTS/truststore.p12" ] || { echo "run make-certs.sh first ($CERTS lacks broker.p12/truststore.p12)" >&2; exit 1; }
mkdir -p "$DATA/credentials"; umask 077
[ ! -e "$DATA/server.properties" ] || { echo "$DATA is already set up; refusing to overwrite." >&2; exit 1; }
STOREPASS="$(cat "$CERTS/store-password.txt")"
ADMIN_PW="$(openssl rand -hex 20)"; BACKEND_PW="$(openssl rand -hex 20)"
printf '%s' "$ADMIN_PW" > "$DATA/credentials/admin.password"; printf '%s' "$BACKEND_PW" > "$DATA/credentials/backend.password"
CERTS_ABS="$(cd "$CERTS" && pwd)"; DATA_ABS="$(cd "$DATA" && pwd)"

cat > "$DATA/server.properties" <<PROPS
# Single-node KRaft broker. CLIENTS (backend, sites, you) use the BROKER listener: TLS + SCRAM login.
# The CONTROLLER listener is only for the broker talking to itself: loopback only, TLS with a client certificate.
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:9093
listeners=BROKER://0.0.0.0:$PORT,CONTROLLER://127.0.0.1:9093
advertised.listeners=BROKER://$HOST:$PORT
listener.security.protocol.map=BROKER:SASL_SSL,CONTROLLER:SSL
controller.listener.names=CONTROLLER
inter.broker.listener.name=BROKER

sasl.enabled.mechanisms=SCRAM-SHA-512
sasl.mechanism.inter.broker.protocol=SCRAM-SHA-512
listener.name.broker.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="admin" password="$ADMIN_PW";

ssl.keystore.type=PKCS12
ssl.keystore.location=$CERTS_ABS/broker.p12
ssl.keystore.password=$STOREPASS
ssl.key.password=$STOREPASS
ssl.truststore.type=PKCS12
ssl.truststore.location=$CERTS_ABS/truststore.p12
ssl.truststore.password=$STOREPASS
ssl.client.auth=none
listener.name.controller.ssl.client.auth=required

# Nothing is allowed unless an ACL says so. Only the admin account and the broker itself bypass this.
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
super.users=User:admin;User:CN=kafka-broker
allow.everyone.if.no.acl.found=false
auto.create.topics.enable=false

log.dirs=$DATA_ABS/logs
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
PROPS

# client settings for the admin tools (used by bootstrap-topic.sh and add-site.sh)
cat > "$DATA/admin.properties" <<PROPS
bootstrap.servers=$HOST:$PORT
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="admin" password="$ADMIN_PW";
ssl.truststore.type=PKCS12
ssl.truststore.location=$CERTS_ABS/truststore.p12
ssl.truststore.password=$STOREPASS
PROPS

CLUSTER_ID="$("$KAFKA_HOME/bin/kafka-storage.sh" random-uuid)"
"$KAFKA_HOME/bin/kafka-storage.sh" format -t "$CLUSTER_ID" -c "$DATA/server.properties" \
  --add-scram "SCRAM-SHA-512=[name=admin,password=$ADMIN_PW]" \
  --add-scram "SCRAM-SHA-512=[name=backend,password=$BACKEND_PW]" >/dev/null

# what the backend needs (environment variables of the backend process)
cat > "$DATA/backend.env" <<ENV
# Settings for the central backend. Keep it private: it holds a password.
# Shell / systemd format (values in single quotes): load with  set -a; . backend.env; set +a   or use EnvironmentFile= in systemd.
# For Docker's --env-file or compose, remove the single quotes (Docker keeps them as part of the value).
SPRING_KAFKA_BOOTSTRAP_SERVERS='$HOST:$PORT'
SPRING_KAFKA_SECURITY_PROTOCOL='SASL_SSL'
SPRING_KAFKA_PROPERTIES_SASL_MECHANISM='SCRAM-SHA-512'
SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG='org.apache.kafka.common.security.scram.ScramLoginModule required username="backend" password="$BACKEND_PW";'
SPRING_KAFKA_SSL_TRUST_STORE_LOCATION='file:$CERTS_ABS/truststore.p12'
SPRING_KAFKA_SSL_TRUST_STORE_TYPE='PKCS12'
SPRING_KAFKA_SSL_TRUST_STORE_PASSWORD='$STOREPASS'
# the topic is created once by bootstrap-topic.sh; the backend has no permission to create or change topics
SPRING_KAFKA_ADMIN_AUTO_CREATE='false'
ENV
chmod 600 "$DATA"/credentials/* "$DATA"/admin.properties "$DATA"/backend.env "$DATA"/server.properties
echo "Broker prepared in $DATA (accounts: admin, backend; passwords in $DATA/credentials, backend settings in $DATA/backend.env)."
echo "Start it:  $KAFKA_HOME/bin/kafka-server-start.sh $DATA/server.properties"
