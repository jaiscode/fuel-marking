#!/usr/bin/env bash
# Creates a private certificate authority and a broker certificate for Kafka, and the truststore clients need.
#   ./make-certs.sh OUT_DIR BROKER_HOSTNAME [EXTRA_SAN ...]      e.g.  ./make-certs.sh ./certs kafka.example.com DNS:kafka-internal IP:10.0.0.5
# BROKER_HOSTNAME must be the name the marking sites use to reach the broker: clients check it against the certificate.
# Produces (in OUT_DIR):
#   ca.crt / ca.key        the CA. Keep ca.key offline and safe; it can mint certificates the sites would trust.
#   broker.p12             the broker's keystore (certificate + key).     -> broker only
#   truststore.p12         contains only the CA certificate.              -> every client (backend, each site)
#   store-password.txt     password of both stores
# For a real deployment you may prefer certificates from your organisation's CA: keep the same file names and formats.
set -euo pipefail
[ $# -ge 2 ] || { sed -n '2,13p' "$0"; exit 1; }
OUT="$1"; HOST="$2"; shift 2
command -v openssl >/dev/null && command -v keytool >/dev/null || { echo "needs openssl and keytool (a JDK)" >&2; exit 1; }
mkdir -p "$OUT"; cd "$OUT"; umask 077
[ ! -e ca.key ] || { echo "$OUT already holds a CA (ca.key); refusing to overwrite. Use a new folder." >&2; exit 1; }

PASS="$(openssl rand -hex 16)"
SAN="DNS:localhost,IP:127.0.0.1"
if [[ "$HOST" =~ ^[0-9.]+$ ]]; then SAN="$SAN,IP:$HOST"; else SAN="$SAN,DNS:$HOST"; fi
for extra in "$@"; do SAN="$SAN,$extra"; done

openssl req -x509 -newkey rsa:4096 -nodes -keyout ca.key -out ca.crt -days 3650 -subj "/CN=Fuel Marking Kafka CA" 2>/dev/null
# The broker's subject is fixed (CN=kafka-broker): the controller listener uses it as the broker's identity.
openssl req -newkey rsa:2048 -nodes -keyout broker.key -out broker.csr -subj "/CN=kafka-broker" 2>/dev/null
openssl x509 -req -in broker.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out broker.crt -days 825 -sha256 \
  -extfile <(printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth,clientAuth\nkeyUsage=digitalSignature,keyEncipherment\n' "$SAN") 2>/dev/null
openssl pkcs12 -export -inkey broker.key -in broker.crt -certfile ca.crt -name kafka-broker -out broker.p12 -passout "pass:$PASS"
keytool -importcert -noprompt -alias fuelmarking-ca -file ca.crt -keystore truststore.p12 -storetype PKCS12 -storepass "$PASS" >/dev/null 2>&1
printf '%s\n' "$PASS" > store-password.txt
rm -f broker.csr broker.key broker.crt ca.srl
echo "Certificates written to $OUT (broker names: $SAN). Valid: CA 10 years, broker 825 days - set a reminder to renew."
