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
| `CENTRAL_DB_POOL_SIZE` / `CENTRAL_DB_CONNECT_TIMEOUT_MS` | 25 / 10000 | Connections the backend may hold open, and how long a request waits for one before failing. One pool serves site syncs, the web app and the master-data drain; keep it below Postgres `max_connections`. Watch Hikari's pending-connections metric if many sites sync. |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka the backend publishes master data to |
| `OIDC_*` | see [oidc-setup.md](oidc-setup.md) | Sign-in for the web app. `OIDC_ISSUER_URI` is required |
| `fuelmarking.master-data-topic` | `fuelmarking.master-data` | Compacted, single-partition topic (created automatically) |
| `fuelmarking.master-data-replicas` | `1` | Set to 3 on a real cluster |
| `fuelmarking.sync-sequencer-ms` | `1000` | How often pushed transfers are numbered for pulling; a transfer reaches the other site at its first pull after that |

### Upgrading the central backend
* **To the release with migration V18 (transfers numbered after commit): stop every running backend instance first**, then
  start the new version (Flyway migrates on start-up). An older instance still running against the migrated database would
  number transfers while pushing again - the race this release fixes, which can silently lose transfers for the receiving
  site. Desktops need no change and keep their pull cursors.
* V18 builds indexes on `synced_entities` without `CONCURRENTLY`: writes to that table wait until they are built (seconds on
  a typical database, longer with millions of rows). Sites simply retry; plan the upgrade for a quiet moment.
* After the upgrade a pushed transfer is pulled by the other site after the next numbering run (`fuelmarking.sync-sequencer-ms`).
* The same release tightens what sites may push; refused records are parked on the site (see "A rejected record" below):
  a push that changes a transfer's two sites, or touches a transfer between two other sites, is refused ("Transfer parties
  cannot change" / "Transfer belongs to other sites"); dates must lie in years 1-9999; barcodes may be at most 100
  characters and must not contain control or invisible formatting characters (spaces of any kind and the GS1 group
  separator are fine); a transfer may list at most 1000 containers; ids must be lower-case UUIDs (as the desktop sends them).
* Records now belong to the site that first stored them. Containers and transfers are shared (custody changes hands;
  a transfer's two sites never change); every other record - shifts, truck markings, incidents, stock counts, signatures -
  can only be updated by the site that stored it, and a signature never changes once stored. New refusals:
  "Record belongs to another site", "Signature is for another site's record" (a site may sign only its own records, or a
  transfer it is one of the two sites of), "Signatures cannot be changed", and "Bad entityType" for a signature of
  anything other than a truck marking, shift, stock count or transfer.
  **Before upgrading, look for rows another site has already taken over.** The new rules lock each row to whoever stored
  it last before the upgrade, so investigate (and if needed restore from the original site's database) anything these
  return. A takeover always rewrote the payload's `siteId` too, so a row cannot be caught by comparing those two; these
  queries instead follow the links between records, and each should return no rows:
  ```sql
  -- truck markings, incidents and stock counts whose shift was stored by a different site
  SELECT m.entity_type, m.entity_id, m.source_site_id AS row_site, s.source_site_id AS shift_site
  FROM synced_entities m
  JOIN synced_entities s ON s.entity_type = 'SHIFT' AND s.entity_id = m.payload ->> 'shiftId'
  WHERE m.entity_type IN ('TRUCK_MARKING', 'INCIDENT', 'STOCK_COUNT') AND m.source_site_id <> s.source_site_id;
  -- stock counts whose lines point at an incident stored by a different site
  SELECT c.entity_id AS stock_count, c.source_site_id AS row_site, i.entity_id AS incident, i.source_site_id AS incident_site
  FROM synced_entities c
  CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(c.payload -> 'lines') = 'array' THEN c.payload -> 'lines' ELSE '[]' END) l
  JOIN synced_entities i ON i.entity_type = 'INCIDENT' AND i.entity_id = l ->> 'incidentId'
  WHERE c.entity_type = 'STOCK_COUNT' AND c.source_site_id <> i.source_site_id;
  -- records signed off by a person who belongs to a different site (a lead, not proof: people can move between sites)
  SELECT r.entity_type, r.entity_id, r.source_site_id AS row_site, u.site_id AS person_site
  FROM synced_entities r
  JOIN app_users u ON u.id::text = COALESCE(r.payload ->> 'supervisorId', r.payload ->> 'reportedBy',
                                            r.payload ->> 'countedBy', r.payload ->> 'signedBy')
  WHERE r.entity_type IN ('SHIFT', 'INCIDENT', 'STOCK_COUNT', 'SIGNATURE') AND u.site_id IS NOT NULL AND u.site_id <> r.source_site_id;
  -- signatures stored by a site that does not own the signed record (or is not one of a transfer's two sites)
  SELECT s.entity_id AS signature, s.source_site_id, s.payload ->> 'entityType' AS signed_type, s.payload ->> 'entityId' AS signed_id
  FROM synced_entities s
  JOIN synced_entities r ON r.entity_type = s.payload ->> 'entityType' AND r.entity_id = s.payload ->> 'entityId'
  WHERE s.entity_type = 'SIGNATURE'
    AND CASE WHEN r.entity_type = 'TRANSFER'
             THEN s.source_site_id::text NOT IN (r.payload -> 'transfer' ->> 'fromSiteId', r.payload -> 'transfer' ->> 'toSiteId')
             ELSE s.source_site_id <> r.source_site_id END;
  ```
  These only find takeovers that left a trace in a linked record. A takeover of a record nothing else points at (for
  example a shift with no markings, incidents, counts or signatures yet) cannot be found from central data alone. The
  conclusive check is per site: export the ids of the site's own records from its desktop database (`truck_markings`,
  `shifts`, `incidents`, `stock_counts`, `signatures`) and confirm `synced_entities.source_site_id` is that site for each.
* Development and test databases only: a database that ran a pre-release build of this change applied an earlier draft of
  V18, and Flyway will refuse to start (checksum mismatch). Recreate it, or drop the draft objects and let V18 run again:
  `DROP INDEX IF EXISTS idx_synced_entities_unnumbered, idx_synced_transfers_unnumbered, idx_synced_transfers_items,
  idx_audit_log_sync_conflict, idx_audit_log_action, uq_audit_log_dedupe_key; ALTER TABLE audit_log DROP COLUMN IF EXISTS
  dedupe_key; DELETE FROM flyway_schema_history WHERE version = '18';` (`flyway repair` alone is not enough: the final V18
  adds objects the drafts did not).

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
* **Audit**: `GET /api/admin/audit` lists master-data changes with who made them. Add `?action=SYNC_CONFLICT` to see custody
  conflicts: a site dispatched a container that is already in another site's open transfer, or that central records at
  another site. Such pushes are accepted (sites work offline); the entry names the transfers and sites so head office can
  sort it out. Other actions can be filtered the same way (e.g. `?action=API_KEY_ISSUED`).

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
