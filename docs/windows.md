# Running the desktop app on Windows

## Status, honestly
**The app has not been run on Windows by its author** (it was built on a Mac with no Windows machine available). What *has* been
done and checked, and what still needs a real Windows test:

| Checked (on macOS/Linux-style tooling) | Not yet checked - needs a Windows machine |
|---|---|
| A Windows jar can be built from any computer and contains the Windows JavaFX libraries (`glass.dll`, `prism_d3d.dll`, `javafx_font.dll`, the C runtime) and none from other systems | That those libraries actually load and draw on a real Windows PC (graphics drivers, display scaling) |
| The database migrations validate with Windows (CRLF) line endings | PostgreSQL for Windows behaving as on macOS |
| Settings come from an `application-local.yml` beside the jar, including paths with spaces/backslashes and the long Kafka login line; the jar finds it from a folder with a space and an accent, even when started elsewhere | The launcher scripts (`.cmd`) |
| CSV exports carry a byte-order mark so Excel reads accents and non-Latin names | Excel opening them on your machines |
| `--selfcheck` checks Java, settings, database, JavaFX, central system, Kafka and scale | The signature pad with a touch screen or pen; a serial scale (no scale driver exists yet) |

The quickest way to find out is the **self-check** below: run it on the Windows PC and send me the result.

## What you need on the site PC
* Windows 10 or 11, 64-bit.
* **Java 21** (a runtime is enough; e.g. Eclipse Temurin 21 JRE). `java -version` must say 21 or newer.
* **PostgreSQL 16** (the official Windows installer). Note the password you give the `postgres` user.
* The three files from `deploy/windows/` plus the jar: `fuel-marking-desktop-win.jar`, `start-fuel-marking.cmd`,
  `check-installation.cmd`, and your `application-local.yml`, all in one folder, e.g. `C:\FuelMarking`.

## Building the Windows jar
From any computer with Java 21 and Maven (a Mac or Linux is fine; the jar contains Windows' JavaFX libraries):
```
mvn -DskipTests package -Djavafx.platform=win -Ddesktop.jar.name=fuel-marking-desktop-win
```
The result is `target/fuel-marking-desktop-win.jar` (about 32 MB). Building on Windows itself with a plain
`mvn -DskipTests package` should also work (untested); then rename the jar or edit the two `.cmd` files. The CI workflow
(`.github/workflows/build.yml`, never run yet) builds on all three systems and runs the tests there.

## Installing
1. Install Java 21 and PostgreSQL 16.
2. Create the database (pgAdmin, or in a command prompt: `"C:\Program Files\PostgreSQL\16\bin\createdb" -U postgres fuel_marking`).
3. Copy the jar and the two `.cmd` files to `C:\FuelMarking`.
4. Create `C:\FuelMarking\application-local.yml`. Only the settings that differ from the built-in ones are needed:
   ```yaml
   database:
     username: "postgres"
     password: "the password you chose for PostgreSQL"
   sync:
     enabled: true
     site-id: "the site's id from the web app"
     api-key: "the key from Administration -> Sites -> New API key"
     base-url: "https://marking.example.com"
     kafka:
       bootstrap-servers: "kafka.example.com:9094"
       properties:
         security.protocol: SASL_SSL
         sasl.mechanism: SCRAM-SHA-512
         sasl.jaas.config: 'org.apache.kafka.common.security.scram.ScramLoginModule required username="site-..." password="...";'
         ssl.truststore.type: PKCS12
         ssl.truststore.location: 'C:\FuelMarking\truststore.p12'
         ssl.truststore.password: "..."
   ```
   Head office produces the whole `sync:` part for you: `deploy/kafka-secure/add-site.sh` writes `<site id>.yml`; add the
   `api-key` and `base-url` lines to it and save it as `application-local.yml`. Copy `truststore.p12` next to it.
5. **Protect the file** - it holds passwords. In a command prompt:
   ```
   icacls C:\FuelMarking\application-local.yml /inheritance:r /grant:r "%USERNAME%:R" "SYSTEM:R"
   ```
   (or restrict the folder to the people who run the app).
6. Double-click **`check-installation.cmd`** and read the result (next section). When nothing fails, double-click
   **`start-fuel-marking.cmd`**. To start it at logon put a shortcut to it in `shell:startup`.

## The self-check
`check-installation.cmd` runs `java -jar fuel-marking-desktop-win.jar --selfcheck` and saves the output in `selfcheck-result.txt`:
```
[ OK ] Java           Java 21.0.4 (Eclipse Adoptium) on Windows 11 amd64, time zone Africa/Nairobi
[ OK ] Settings       built-in settings plus C:\FuelMarking\application-local.yml
[ OK ] Database       jdbc:postgresql://localhost:5432/fuel_marking as postgres: PostgreSQL 16.4; 11 migrations applied
[ OK ] JavaFX         graphics toolkit started (21)
[ OK ] Central        https://marking.example.com reachable and it accepts this site's credentials
[ OK ] Kafka          kafka.example.com:9094 (SASL_SSL): connected and the master-data topic is readable
```
It never prints passwords or keys. What the problems mean:

| Line | Meaning and fix |
|---|---|
| `[FAIL] Java` | Java older than 21. Install Java 21. |
| `[FAIL] Database` | PostgreSQL is not running, the database does not exist, or the username/password in `application-local.yml` is wrong. |
| `[FAIL] JavaFX` | The graphics libraries could not start. Make sure you use the **Windows** jar (`-Djavafx.platform=win`), update the graphics driver, and send me the line. |
| `[FAIL] Central ... REJECTS` | The site id or API key is wrong or was re-issued: issue a new key in the web app. |
| `[FAIL] Central ... cannot be reached` | Wrong address, no network, or a firewall/proxy: the PC needs outbound HTTPS to head office. |
| `[FAIL] Kafka ... Authentication failed` | The Kafka login in `application-local.yml` is wrong or was revoked. |
| `[FAIL] Kafka ... SSL` | The truststore path is wrong, or the server name does not match the certificate. |
| `[WARN] Scale` | Not a failure: the configured scale is not answering. |

## Windows test checklist (about 20 minutes)
Run these on a real Windows PC and note anything that differs:
1. `check-installation.cmd`: every line `[ OK ]` (Scale may say `[INFO]` simulator). **Send me `selfcheck-result.txt`.**
2. `start-fuel-marking.cmd`: the sign-in screen appears; the scale simulator window opens beside it.
3. Text is crisp and not cut off at your display scaling (100%, 125%, 150%).
4. Sign in with a PIN (a wrong PIN says how many attempts are left).
4b. As a supervisor, before starting the shift only **Shift** and **Reports** are listed on the left; the rest appears after **Start shift**.
5. Register a jerrican in the web app (Admin -> Jerricans; the container types come from Admin -> Container types), then as the warehouse manager receive it empty and refill and seal it (Jerricans -> Receive jerrican, then Refill jerrican). Transfer it to a marking site, receive it there, start a shift, open the jerrican with its seal(s), register a truck.
5b. Register the truck by compartments: each compartment has its own fuel, and each fuel type is marked with one rum type (Admin -> Fuel types in the web app); each compartment is then dosed on its own (target and measured weight are shown), from a jerrican of its fuel's rum type.
6. In the simulator window put `0.350`, press **Tare scale** in the Dose screen, put `0.450`, **Read scale** -> 100.0 g.
7. Sign with the mouse (and with touch or a pen if you have one): the signature appears and is saved.
8. Verify the truck, then open **Reconcile** in the left menu: type the count of the opened jerrican (try 50 g less and choose a cause - it becomes an incident). Close the shift (**End shift & reconcile** counts again): variance 0.
   End of shift: the Shift tab shows a checklist and **End shift** stays locked until every truck is verified and no jerrican is open. On Jerricans -> Close jerrican select a jerrican that still holds rum and **Close jerrican back & sign** with a NEW seal number (the old seal is refused); it is sealed stock again and the next shift opens it with the new seal.
   Finished jerrican: on Jerricans -> Declare empty select it and **Declare empty & sign**, then Jerricans -> Transfer jerrican -> choose the warehouse -> **Dispatch**; as the warehouse manager receive it (it arrives EMPTY) and refill it with new seals.
9. Reports -> Marking summary -> **Export CSV**: open it in Excel; accents and names look right.
10. Leave the app untouched for 10 minutes: it signs out by itself.
11. With sync on: a change made in the web app (a new oil company) appears in Register truck within a minute or two.
12. Unplug the network, register a truck, plug it back: it appears in the web app.

## Backups
Back up the site's PostgreSQL daily: it holds anything not yet synced. Example (Task Scheduler, daily):
```
"C:\Program Files\PostgreSQL\16\bin\pg_dump" -U postgres -Fc -f "D:\Backups\fuel_marking_%DATE:~-4%%DATE:~4,2%%DATE:~7,2%.dump" fuel_marking
```
(set `PGPASSWORD` or use a `pgpass.conf` for the account running the task).

## Network and security notes
* The PC needs **outbound** access only: HTTPS to head office and the Kafka port (9094). No inbound ports are opened.
* `application-local.yml` is a password file: restrict it (step 5) and do not email it. The PINs of the people who sign in
  are never stored in it.
* Windows Defender or other antivirus sometimes scans a freshly copied jar for a while on first start: allow a minute.
* Windows on ARM and 32-bit Windows are not supported by this build (the Windows jar is for 64-bit Intel/AMD PCs).
