# Fuel Marking System

Marks fuel trucks with rum at marking sites, tracks every container of rum from warehouse to jerrican, and gives head
office a live picture of it all.

```
 marking site (desktop, offline-capable)                      head office
 ┌──────────────────────────────────────┐        push        ┌──────────────────────────────────────────┐
 │ JavaFX app + local PostgreSQL        │ ─── outbox ──────► │ Spring Boot backend + Angular web app    │
 │  PIN sign-in, shifts, seals,         │   (HTTPS, site     │  central PostgreSQL, typed reports,      │
 │  register → dose → dispense → verify,│    API key)        │  OIDC sign-in, master-data admin         │
 │  transfers, incidents, reports       │ ◄── transfers ──── │                                          │
 │                                      │ ◄── master data ── │ Kafka (compacted topic) ◄── outbox ──────│
 └──────────────────────────────────────┘     (Kafka)        └──────────────────────────────────────────┘
```

| Folder | What it is |
|---|---|
| `src/` | Desktop app (Java 21, JavaFX, PostgreSQL, Flyway). Built to `target/fuel-marking-desktop.jar`. |
| `backend/` | Central backend (Spring Boot 3, PostgreSQL, Kafka, OIDC resource server). Serves the web app too. |
| `web/` | Web app (Angular 18). `npm run build:embed` copies it into the backend. |
| `docs/` | [Deployment & operations](docs/deployment.md), [OIDC setup](docs/oidc-setup.md), [Hardware & scale simulator](docs/hardware.md), [Windows](docs/windows.md). |
| `deploy/` | `kafka-secure/`: scripts to run Kafka with TLS, logins and per-site permissions ([README](deploy/kafka-secure/README.md)). |
| `scripts/` | `e2e-full.sh`: full-stack check against a real Kafka broker. `e2e-keycloak.sh`: browser sign-in against a real Keycloak. `e2e-kafka-secure.sh`: the secured Kafka set-up under attack. |

## Run everything on this machine

```bash
./scripts/dev-up.sh      # PostgreSQL, Keycloak, secured Kafka, backend + web app, and a desktop for one site - seeded and wired together
./scripts/dev-down.sh    # stop it all (add --wipe to forget the data)
```
The first run builds the jars, creates everything under `.dev/` and prints the web addresses, sign-in names and passwords;
later runs reuse the data. Development only (fixed sample people, plain-HTTP Keycloak, a setup client with a secret on disk).

## Try the desktop on its own

Needs Java 21, Maven, Node 20 and a PostgreSQL you can create databases in.

```bash
# desktop app with sample data (every user's PIN is 246810)
createdb fuel_marking
mvn package -DskipTests
java -Ddev.seed=true -Ddatabase.url=jdbc:postgresql://localhost:5432/fuel_marking \
     -Ddatabase.username=$USER -Ddatabase.password= -jar target/fuel-marking-desktop.jar
```

For the central system see [docs/deployment.md](docs/deployment.md) (Docker Compose or plain jars).

## Tests

| Command | Covers |
|---|---|
| `mvn test -Dtest='*IT,*Test' -DargLine="-Ddatabase.url=jdbc:postgresql://localhost:5432/fuel_marking ..."` | Desktop: workflow, transfers, reports, sync, PIN login, **every JavaFX screen driven through a full operating day** (screenshots in `target/ui-shots`) |
| `cd backend && mvn test -Dtest='*IT,*Test'` | Backend: sync API, read API, OIDC authorization, master-data outbox, audit (needs databases `fuel_central_test`) |
| `cd web && npx ng test --watch=false --browsers=ChromeHeadless` | Web app: API client, sign-in, guards, chart |
| `scripts/e2e-full.sh` | Everything together with a real Kafka broker, an OIDC provider, the backend and desktop code |
| `KAFKA_HOME=... scripts/e2e-kafka-secure.sh` | The secured broker (real Apache Kafka): wrong passwords, no TLS, plaintext, other sites' groups, writing, other topics, revocation; backend and desktop through it |
| `KEYCLOAK_HOME=... scripts/e2e-keycloak.sh` | The web app's sign-in against a real Keycloak: PKCE, roles, audience, silent token renewal, sign-out (see [docs/oidc-setup.md](docs/oidc-setup.md)) |

`*IT` classes are skipped by a plain `mvn test`; name them with `-Dtest` as above.

The backend's integration tests all use one PostgreSQL database, set in `backend/src/test/resources/it-database.properties`:
by default `jdbc:postgresql://localhost:55432/fuel_central_test` as user `postgres` with no password. Start a throwaway
cluster for them with trust authentication (data in a temporary directory; never the development data in `.dev`):

```bash
initdb -D /tmp/fuel-it -U postgres --auth=trust
pg_ctl -D /tmp/fuel-it -o "-p 55432 -c listen_addresses=127.0.0.1" -l /tmp/fuel-it.log start
createdb -h 127.0.0.1 -p 55432 -U postgres fuel_central_test
cd backend && mvn test -Dtest='*IT,*Test'
pg_ctl -D /tmp/fuel-it stop
```

To use another database, set `IT_DB_URL`, `IT_DB_USER` and `IT_DB_PASSWORD` (empty by default; keep passwords out of the
repository). The backend needs Java 21.
