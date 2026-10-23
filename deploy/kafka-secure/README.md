# Secured Kafka for the Fuel Marking System

Kafka carries master data (sites, oil companies, **users and their PIN hashes**) from head office to every marking site.
This folder sets up a broker where:

* **every connection is encrypted** (TLS) and nothing is accepted without a login (SCRAM-SHA-512; weaker methods are off);
* **nothing is allowed unless a permission says so**: the backend can only *write* the master-data topic; each site can
  only *read* that topic, using *its own* consumer group; nobody else can do anything but the `admin` account;
* a site can be **revoked** instantly, and its password **rotated**.

It was verified on a real Apache Kafka 3.8.0 broker by `scripts/e2e-kafka-secure.sh` (see the end of this page).
The shipped scripts need `bash`, `openssl` and `keytool` (a JDK) and an unpacked Apache Kafka 3.5 or newer.

## Set-up (once)

```bash
KAFKA_HOME=/opt/kafka          # the unpacked Apache Kafka
DATA=/srv/kafka-secure         # config, logs and credentials (keep it private)
HOST=kafka.example.com         # the name the marking sites use to reach the broker

./make-certs.sh  $DATA/certs $HOST                       # a private CA + the broker certificate + the clients' truststore
./setup-broker.sh $KAFKA_HOME $DATA $DATA/certs $HOST    # config, accounts "admin" and "backend", storage format
$KAFKA_HOME/bin/kafka-server-start.sh $DATA/server.properties   # run it as a service, see below
./bootstrap-topic.sh $KAFKA_HOME $DATA                   # once the broker is up: the compacted topic + the backend's right to write it
```

`HOST` must be the name sites connect to: clients check it against the certificate. Add more names with
`make-certs.sh $DATA/certs $HOST DNS:other-name IP:10.0.0.5`. If you prefer certificates from your organisation's CA, keep
the same file names and formats (`broker.p12` for the broker, `truststore.p12` holding the CA for clients).

### The central backend
`setup-broker.sh` wrote `$DATA/backend.env`. Give those settings to the backend process (they hold its password):
```bash
set -a; . $DATA/backend.env; set +a        # or EnvironmentFile=$DATA/backend.env in a systemd unit
java -jar fuel-marking-central.jar
```
For Docker's `--env-file` / compose, remove the single quotes around the values (Docker keeps them as part of the value).
The topic is created once by `bootstrap-topic.sh`; the backend deliberately has **no** right to create, change or read it.

### Each marking site
```bash
./add-site.sh $KAFKA_HOME $DATA $DATA/certs <SITE_ID>       # SITE_ID = the site's id in the web app (the desktop's sync.site-id)
```
It creates the login `site-<SITE_ID>`, lets it read the topic with the consumer group `fuelmarking-site-<SITE_ID>` only, and
writes `$DATA/sites/<SITE_ID>.env`. It also writes `<SITE_ID>.yml`, the same settings as an `application-local.yml` (no shell quoting; the easiest form, and the one to
use on Windows). Hand one of them to the site **privately**, together with `truststore.p12` (edit the truststore path to where
you put it). On Linux/macOS you can instead load the `.env` file's variables before starting the app
(`set -a; . site.env; set +a`, or the service's environment). The desktop's other settings are `SYNC_ENABLED=true`,
`SYNC_SITE_ID`, `SYNC_API_KEY`, `SYNC_BASE_URL`.

A desktop re-reads the whole master-data topic every time it starts, so a **reinstalled or restored** site PC gets its sites, companies and
users again without anyone touching Kafka (the topic is small: it holds only the latest version of each record).

## Day to day
| Task | Command |
|---|---|
| New site | `add-site.sh ... <SITE_ID>` |
| **Site machine lost / site closed** | `remove-site.sh $KAFKA_HOME $DATA <SITE_ID>`: login and permissions deleted; new connections are refused at once, an open one lasts until it reconnects (minutes) |
| Rotate a site's password | `add-site.sh ... <SITE_ID>` again, send the new file, the old password stops working immediately |
| Rotate the backend's password | `kafka-configs.sh --alter --add-config 'SCRAM-SHA-512=[password=NEW]' --entity-type users --entity-name backend`, then update `backend.env` and restart the backend |
| Renew certificates | the broker certificate lasts 825 days, the CA 10 years: make new ones with `make-certs.sh` into a new folder, point `server.properties` at them and restart; clients only need a new `truststore.p12` if the **CA** changed |
| See who may do what | `kafka-acls.sh --bootstrap-server $HOST:9094 --command-config $DATA/admin.properties --list` |

Keep `$DATA/credentials`, `admin.properties`, `ca.key` and the settings files readable only by the people who run the
system. `ca.key` can mint certificates the sites would trust: store it offline once the certificates are made.

## Running the broker as a service (systemd example)
```ini
[Unit]
Description=Fuel Marking Kafka
After=network.target
[Service]
User=kafka
ExecStart=/opt/kafka/bin/kafka-server-start.sh /srv/kafka-secure/server.properties
Restart=on-failure
LimitNOFILE=100000
[Install]
WantedBy=multi-user.target
```
Open **only** the broker port (9094 by default) in the firewall. The controller listener (9093) is bound to the loopback
interface and must stay unreachable from outside.

## What was verified, and what was not
`scripts/e2e-kafka-secure.sh` runs the scripts above on a real Kafka 3.8.0 broker with the real backend and desktop code:

* a backend with a **wrong password** keeps answering admin requests in a fraction of a second, queues every change, and the
  admin status says why ("Authentication failed ... SCRAM-SHA-512"); once the password is fixed the backlog is delivered;
* a site desktop configured only from its own settings file reads the master data over TLS + SCRAM, and the person set up in
  the web app can sign in with the PIN;
* refused: wrong password, unknown account, one site's password used with another site's account, a client that does not
  trust the certificate, a plaintext connection, a login without TLS, the `PLAIN` and `SCRAM-SHA-256` methods;
* refused: a site using another site's consumer group, a site writing to the topic, a site reading or even seeing other
  topics, the backend reading the topic back or creating/deleting topics;
* a revoked site (login and permissions removed) is refused.

Not covered: a multi-broker cluster (set `bootstrap-topic.sh`'s replication factor and give each broker a node id and the
quorum list; the permissions replicate with the cluster), Kafka under Docker (`docker-compose.yml` still starts a
**plaintext** broker for evaluation only), and certificates from a public or corporate CA. Run the check yourself:
```bash
KAFKA_HOME=/path/to/kafka_2.13-3.8.0 ./scripts/e2e-kafka-secure.sh      # about five minutes
```
