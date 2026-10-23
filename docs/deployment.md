# Deployment and operations

Two kinds of installation: **one central system** and **one desktop per marking site (or mobile unit)**.

## 1. Central system

Needs: PostgreSQL 16, Kafka 3.x, an OpenID Connect provider ([docs/oidc-setup.md](oidc-setup.md); a verified Keycloak realm file is supplied), HTTPS in front.

### With Docker Compose (evaluation / small deployments)
```bash
cp .env.example .env        # set POSTGRES_PASSWORD, OIDC_ISSUER_URI, KAFKA_PUBLIC_HOST, ...
docker compose up -d --build
```
Web app and API: `http://<host>:8080`. Put a TLS-terminating reverse proxy in front (the backend honours
`X-Forwarded-*`). **The compose file has not been run by its author** (no Docker was available when it was written; it
passes `docker compose config`). Expect to adjust it, and read the Kafka security section below first.

### Without Docker
```bash
cd web && npm ci && npm run build:embed        # builds the web app into the backend
cd ../backend && mvn -DskipTests package
OIDC_ISSUER_URI=... OIDC_AUDIENCE=... KAFKA_BOOTSTRAP_SERVERS=... \
CENTRAL_DB_URL=jdbc:postgresql://db:5432/fuel_central CENTRAL_DB_USER=... CENTRAL_DB_PASSWORD=... \
java -jar target/fuel-marking-central-1.0-SNAPSHOT.jar
```
Flyway creates the schema on first start. `GET /actuator/health` is the readiness probe.

### Backend settings
| Variable | Default | Meaning |
|---|---|---|
| `CENTRAL_DB_URL` / `_USER` / `_PASSWORD` | local | Central PostgreSQL |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka the backend publishes master data to |
| `OIDC_*` | see [oidc-setup.md](oidc-setup.md) | Sign-in for the web app. `OIDC_ISSUER_URI` is required |
| `fuelmarking.master-data-topic` | `fuelmarking.master-data` | Compacted, single-partition topic (created automatically) |
| `fuelmarking.master-data-replicas` | `1` | Set to 3 on a real cluster |

### Kafka security: required before real use
Marking sites read **all master data, including users' PIN hashes**, from Kafka, and sites reach it over networks you do not
control. A short PIN can be recovered from its hash in minutes, so the broker must be secured. **`deploy/kafka-secure/`**
does that and is verified end to end (see its README):

* TLS on every connection and a SCRAM-SHA-512 login for every client, with weaker methods switched off;
* deny-by-default permissions: the backend can only **write** the master-data topic, each site can only **read** it with
  **its own** consumer group, and no one but `admin` can do anything else;
* scripts to add a site, **revoke** one (lost machine, closed site) and rotate passwords, and certificate generation.

The backend takes its Kafka login from the settings file the set-up writes (`backend.env`); each desktop takes its own from
`add-site.sh` (`SYNC_KAFKA_PROPERTIES_*` environment variables). The `docker-compose.yml` broker is **plaintext, for
evaluation only**: do not point real sites at it.

## 2. First-time set-up, in order
1. Sign in to the web app as an administrator.
2. **Administration → Oil companies**: add the companies.
3. **Administration → Sites**: add each site and the warehouse. For each site choose **New API key** and copy it (shown once).
4. **Administration → Users**: add people with a role and a site, then **Set PIN** for each (4-8 digits, no simple
   sequences). They cannot sign in at a site until a PIN exists.
5. Install the desktop at each site (below) using that site's id and API key.

The **Sites** page warns if master-data changes are waiting to be delivered (for example if Kafka is down); they are
queued safely and delivered automatically.

## 3. Marking site (desktop)
Each site needs **its own local PostgreSQL** (the app works fully offline and syncs when it can).

```bash
createdb fuel_marking                      # once, on the site's machine
java -jar fuel-marking-desktop.jar         # Java 21 required
```
The jar contains the JavaFX libraries of one operating system: build it for the target with
`-Djavafx.platform=win|linux|mac|mac-aarch64` (see [windows.md](windows.md) for Windows). Run `java -jar ... --selfcheck` on a new
machine to check the installation before starting the app.
Settings are built into the jar; change them with an **`application-local.yml` next to the jar** (only the settings that differ;
the easiest way, especially on Windows), or per setting with `-Dkey=value` / environment variables (the key in upper case with
dots and dashes as underscores). Precedence: `-D`/environment variable, then `application-local.yml`, then the built-in file:

| Setting | Env var | Example |
|---|---|---|
| `database.url` / `.username` / `.password` | `DATABASE_URL` ... | local PostgreSQL |
| `sync.enabled` | `SYNC_ENABLED` | `true` |
| `sync.site-id` | `SYNC_SITE_ID` | the site's id from the web app |
| `sync.api-key` | `SYNC_API_KEY` | the key issued for this site |
| `sync.base-url` | `SYNC_BASE_URL` | `https://marking.example.com` |
| `sync.kafka.bootstrap-servers` | `SYNC_KAFKA_BOOTSTRAP_SERVERS` | `kafka.example.com:9093` |
| `fuel-marking.dose-tolerance-percent` | | how far the weighed dose may differ from the required dose, in percent (default `2.0`, the agreed value for now) |
| `fuel-marking.ui.idle-lock-seconds` | | seconds idle before the screen locks (default 600, `0` = never) |
| `hardware.scale.driver` / `.simulator-panel` | `HARDWARE_SCALE_DRIVER` ... | `simulator` (default), `tcp`, later `serial`; see [hardware.md](hardware.md) |

With sync disabled the app still runs, but the site has no users or oil companies (they arrive from head office).
A site machine that was offline simply catches up on its next connection; nothing is lost.

## 4. Operating it
* **Backups**: back up both PostgreSQL databases. Central holds the complete history; a site's local database holds
  anything not yet synced (`select count(*) from sync_outbox where synced_at is null`).
* **A rejected record**: sync parks records the central system refuses for good (`sync_outbox.last_error` set,
  `synced_at` set). Investigate them; they are not retried.
* **Rotate a site key**: Sites → New API key; update that site's `SYNC_API_KEY`. The old key stops working at once.
* **Reset a PIN / unlock a user**: Users → Set PIN. A new PIN also clears a lockout. Five wrong PINs lock a user for 5 minutes.
* **Master data not reaching sites**: check Sites for the backlog banner and `GET /api/admin/master-data/status`.
  After a Kafka topic loss use **Re-send master data**.
* **Audit**: `GET /api/admin/audit` lists master-data changes with who made them.

## 5. Not covered
* **Hardware**: the barcode scanner works as a keyboard device and signatures are drawn on screen. The **scale is a
  simulator** until the real device is added - see [hardware.md](hardware.md), which also says exactly how to add it.
  Machines that are not for testing should set `hardware.scale.simulator-panel=false`.
* **Dose and units**: 10 g of rum per 1000 L of declared fuel volume (`fuel-marking.rum-dosage-per-1000l`). Every rum quantity
  in the system - doses, container contents and capacities, reconciliation, incident losses, reports - is in **grams**.
  Enter container capacities and contents in grams too (a 1 L jerrican of rum weighs about 1000 g, so enter 1000). Database
  columns and JSON fields whose names end in `_ml` / `Ml` are historical; their values are grams. The scale reads kilograms
  and the app converts to grams (x 1000). Dosing and refilling are **weighed** (tare an empty container, read the net weight): the
  weighed dose must be within `fuel-marking.dose-tolerance-percent` of the requirement and is what is deducted and recorded. If the dose changes, change the setting on every site; doses already recorded keep the value they were
  calculated with.
* **Installers**: the desktop is a jar, not a native installer (`jpackage` is the next step).
* **Multi-site conflicts**: for a record written by two sites the last write wins (containers and transfers change
  custody, so this is deliberate).
