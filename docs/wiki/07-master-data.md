# 7. Master data

Behaviour wiki, module 7. Master data is the shared reference list that every marking site needs: the sites themselves, oil
marketing companies (OMCs), fuel types, rum types, container types, locations, registered jerricans, and the users who sign
in at the sites. Head office owns it, through the web app's **Administration** screens. The marking sites only read it.

Everything in this page is read from the code. Where the code and the intent disagree, or behaviour looks unintended, it is
listed under **Review notes**.

## At a glance

| Ref | Feature | Status | Where | Who | Reaches sites? |
|---|---|---|---|---|---|
| 7.1 | Create OMC (oil marketing company) | Implemented | Web: Administration > Oil companies | Administrator | Yes, via Kafka |
| 7.2 | Create marking site / warehouse | Implemented | Web: Administration > Sites | Administrator | Yes, via Kafka |
| 7.3 | Create container type | Implemented | Web: Administration > Container types | Administrator | Yes, via Kafka |
| 7.4 | Create rum type | Implemented | Web: Administration > Rum types | Administrator | Yes, via Kafka |
| 7.5 | Create fuel type | Implemented | Web: Administration > Fuel types | Administrator | Yes, via Kafka |
| 7.6 | Create location | Implemented | Web: Administration > Locations | Administrator | Yes, via Kafka |
| 7.7 | Create administrative units | **Not found** | None. Closest: Locations (7.6) | - | - |

Module-level topics in this page:

- How master data reaches the sites (outbox, Kafka, desktop consumer). See below.
- Who can change it, and what is recorded (roles and audit). See below.

---

## How master data reaches the sites

Master data is written once, on the central side, and copied to every site. The path is:

1. **Change in the web app.** An administrator saves a record in Administration. The backend writes the record, an audit entry
   and an outbox message in **one database transaction**. If the message broker is down, the change is still saved and the
   message waits.
2. **Outbox drain.** A background job runs every second (`fuelmarking.master-data-drain-ms`, default 1000) and sends pending
   messages in order, up to 100 at a time. Only one backend instance drains at a time (a database advisory lock), so the order
   is kept. The first failure stops the batch; the error text is kept (up to 480 characters) on the message. Delivered rows are
   deleted after 7 days.
3. **Kafka topic.** Messages go to one topic, `fuelmarking.master-data` by default. It has one partition and is **compacted**,
   so it keeps the latest message for each key. The key is `TYPE:id` (for example `SITE:<uuid>`). The value is JSON with the
   record type, the operation (always an upsert) and the payload. The send waits up to 15 seconds for the broker to confirm.
4. **Marking site desktop.** Each site reads the whole topic with its own consumer group (`fuelmarking-site-<site id>`).
   - It replays the topic from the beginning every time it starts, so a reinstalled or restored desktop catches up.
   - Each message is written into the local database as an upsert, overwriting the local copy. Master data is never written to
     the desktop's own outbox.
   - A record that depends on another record that has not arrived yet is **parked and retried**. Examples: a user waits for
     its site; a registered jerrican waits for its container type and its rum type.
   - Any other unreadable record is logged and skipped, and the offset moves on.

**Admin visibility.** The Sites screen shows a notice when changes are waiting: "N change(s) are waiting to be sent to the
sites", with the age of the oldest one and the last error text. The notice says the change is safe and will be delivered
automatically, and suggests the broker may be unavailable if it does not clear within a minute or two.

**Re-send.** The Sites screen has a **Re-send master data** button. It queues every master-data record again (sites, companies,
fuel types, container types, rum types, jerricans, locations and users), records an audit entry, and reports how many records
it queued. Use it after a broker has lost the topic.

**Who receives what.** Every site reads the same topic, so every site receives every record. This includes every user,
**including each user's PIN hash**, whatever site the user is assigned to. The desktop only shows the users for its own site at
sign-in, but the hashes are stored on every desktop. See the Review notes in 7.6 and in the roles section below.

**Desktop registration needs master data.** A desktop with no synced sites shows "No marking sites yet. Master data is created
in the web app and synced here." A user cannot sign in until a site and the user have arrived.

**Review notes (overview)**

1. **PIN hashes on every desktop.** The user record, with its PIN hash, goes to every marking site, not only the user's own
   site. The Kafka security notes describe the topic as readable by every site. Confirm this is acceptable, or restrict the
   user message per site.
2. **Re-send tooltip is incomplete.** The button's tooltip says it sends "all sites, oil companies and users". The action
   also re-queues fuel types, rum types, container types, jerricans and locations.
3. **Skipped records are silent to the admin.** A record that fails for a reason other than a missing dependency is logged on
   the desktop and skipped. Nothing on the web shows that a site skipped it.

---

## Who can change master data, and what is recorded

**Web access roles.** The web app signs people in through the organisation's identity provider (OpenID Connect, with PKCE).
Two roles come from the token's roles claim:

| Web role | Default role name in configuration | What it allows |
|---|---|---|
| Viewer | `fuelmarking-viewer` | Read the dashboards and lists (reports, inventory, shifts, transfers). |
| Administrator | `fuelmarking-admin` | Everything a viewer can do, plus Administration (all the screens in this page). |

A person with neither role sees a "No access" page. The Administration menu is shown only to administrators, and the API
enforces the same rule. The desktop has no administration screens at all: master data is read-only there.

**Desktop roles** are a different thing: they are the job roles of the people who sign in at a site (supervisor, dosing
operator, dispense operator, warehouse manager). They are set on each user in Administration > Users. The roles and what
each sees are in the sign-in summary on the [wiki index](README.md).

**Users in Administration.** Create and edit a user with username (required, up to 60 characters), full name (required, up to
200), role, and site (a specific site or "Any site"). A user must have a PIN before they can sign in; the PIN is set separately,
and is never shown again. A new user's site defaults to the **first site in the list**, not to "Any site" (see Review notes).

**Audit log.** Every change to master data, and every PIN and API-key event, is written to the audit log in the same transaction
as the change.

- The log is available only through the API (`GET /api/admin/audit`, administrators only). It returns the newest entries first,
  up to 500 at a time (default 100), with an optional action filter. **No web screen shows the audit log.**
- Each entry has: time, actor (the display name from the sign-in token), actor subject (the token's subject), action, target
  (the record id), and a short detail (for example the code, and "(inactive)" when deactivated).
- Actions recorded for master data: `SITE_CREATE`, `SITE_UPDATE`, `OMC_CREATE`, `OMC_UPDATE`, `FUEL_TYPE_CREATE`,
  `FUEL_TYPE_UPDATE`, `RUM_TYPE_CREATE`, `RUM_TYPE_UPDATE`, `CONTAINER_TYPE_CREATE`, `CONTAINER_TYPE_UPDATE`,
  `LOCATION_CREATE`, `LOCATION_UPDATE`, `JERRICAN_REGISTER`, `JERRICAN_UPDATE`, `USER_CREATE`, `USER_UPDATE`,
  `MASTER_DATA_REPUBLISH`.
- Actions recorded for credentials: `PIN_SET` (no PIN in the detail) and `API_KEY_ISSUED` (no key in the detail). The code says
  both are never recorded.
- Deactivating a record is an update, recorded as `..._UPDATE` with "(inactive)" in the detail. Nothing is ever deleted.
- Registering a batch of jerricans writes one `JERRICAN_REGISTER` entry per barcode.

**Review notes (roles and audit)**

1. **No audit screen.** The log is API-only. An administrator who needs to see who changed a company must call the API.
2. **New users default to a site.** The new-user form pre-selects the first site in the list. A user created by accident is
   tied to that site rather than to "Any site".
3. **Users can be created without a site.** That is allowed ("Any site"), and such a user can sign in at every site, which is
   worth confirming as intended.

---

## 7.1 Create OMC (oil marketing company)

**Status**: Implemented. The web app creates, edits and deactivates companies. The desktop only reads them.

**Where / who**

- Web: **Administration > Oil companies** (route `/admin/omcs`). Administrator only.
- Desktop: no screen. A company appears in the truck registration list once it has synced, and only while it is active.

**Preconditions**

- Signed in as an administrator, with the API reachable.

**Behaviour**

- The list shows Code, Name and Status (active or inactive), ordered by name. There is no search.
- **New company** opens a form with **Code** (required, up to 30 characters) and **Name** (required, up to 200 characters).
  Save stays disabled until both are filled in. A new company is always created **active**.
- **Edit** opens the same form, with the code still editable.
- **Activate / Deactivate** toggles the flag. A deactivated company is hidden from new truck registrations on the desktop, but
  it stays on every truck already registered under it.
- There is no delete. Records are deactivated.

**Validation rules & error messages**

- Client: Save is disabled until Code and Name are entered.
- Server, blank values: "code is required" and "name is required" (HTTP 400, shown as an error toast).
- Server, duplicate code: HTTP 409 with "Code, username or barcode already exists". The same message covers users and jerricans.
  Uniqueness is enforced by the database, and it is case-sensitive for companies (see Review notes).
- Success toast: "Company saved."

**Data / sync impact**

- One row in `oil_marketing_companies` (id, code, name, active).
- Audit entry `OMC_CREATE` or `OMC_UPDATE`. Outbox message `OMC`.
- Desktop: upserts id, code, name and active.
- On the desktop, each truck stores the company **name** as text (`oil_marketing_company`) as well as its id. Both the desktop
  reports and the web Dashboard group by that name. See 5.1 and the review notes below.

**Review notes**

1. **Renaming and duplicate names.** A truck keeps the name it was registered with. Renaming a company changes only new trucks,
   so one company shows as two rows in the by-company reports. Two companies with the same name merge into one row. No rule
   stops duplicate names.
2. **Codes are not normalised.** Companies are saved with the code and name exactly as typed (no trim, no upper-casing),
   unlike rum types, fuel types, locations and container types, which are trimmed and upper-cased. "abc" and "ABC" can both
   exist.
3. **Update requires the active flag.** An API update that leaves out `active` is read as `false`, which would deactivate the
   company. The web app always sends the flag, so the screen is safe; direct API callers are not. The same applies to sites.
4. **Trailing spaces in the name** are stored as typed.

**Code references**

- Backend: `backend/src/main/java/org/jai/fuelmarking/central/master/AdminController.java` (`/api/admin/omcs`),
  `backend/src/main/java/org/jai/fuelmarking/central/master/MasterDataService.java` (`saveOmc`, `omcs`),
  `backend/src/main/resources/db/migration/V1__central_schema.sql` (`oil_marketing_companies`, unique code).
- Web: `web/src/app/pages/admin/omcs.component.ts`, `web/src/app/core/api.service.ts` (`omcs`, `saveOmc`).
- Desktop: `src/main/java/org/jai/fuelmarking/sync/MasterDataApplier.java` (`omc`),
  `src/main/java/org/jai/fuelmarking/dao/MasterDataDao.java` (`omcs`, active only), `src/main/java/org/jai/fuelmarking/dao/TruckMarkingDao.java`
  (stores the name on each truck).
- Tests: `backend/src/test/java/org/jai/fuelmarking/central/MasterDataOutboxIT.java`.

---

## 7.2 Create marking site / warehouse

**Status**: Implemented. There is one kind of record, the **site**, with a type. A **warehouse** is a site of type WAREHOUSE. There is
no separate warehouse record.

**Where / who**

- Web: **Administration > Sites** (route `/admin/sites`). Administrator only.
- Desktop: no site screen. A site appears at sign-in once it has synced.

**Preconditions**

- Signed in as an administrator.

**Behaviour**

- The list shows Code, Name, Type (fixed, mobile or warehouse, shown in lower case) and Status.
- **New site** opens a form with:
  - **Code**: required, up to 30 characters.
  - **Name**: required, up to 200 characters.
  - **Type**: fixed, mobile or warehouse. A new site defaults to **fixed**.
- A new site is created **active**.
- **Edit** uses the same form. **Activate / Deactivate** toggles the flag.
- **New API key** issues a key for the site's desktop installation. The key is shown **once**, with a Copy button. Issuing a new key
  makes the previous key stop working at once, and the screen asks for confirmation first. The key itself is never stored
  (only a hash) and never written to the audit log.
- The site types mean:
  - **FIXED**: a fixed marking site. Choosing a location at shift start is optional.
  - **MOBILE**: a site that moves. A supervisor must choose a location before starting a shift.
  - **WAREHOUSE**: receives bulk containers and empties. Empty jerricans are returned to a WAREHOUSE site only.

**Validation rules & error messages**

- Blank code or name: "code is required" or "name is required" (HTTP 400).
- Type not one of the three: "siteType must be one of [FIXED, MOBILE, WAREHOUSE]". The order in this list is not fixed.
- Duplicate code: HTTP 409, "Code, username or barcode already exists".
- API key for an unknown site: "Unknown site".
- When the type is left out on create, the API stores FIXED.
- Desktop, mobile site starting a shift with no location: "Choose where <site name> is working before starting the shift".
- Desktop, empties sent to a site that is not a warehouse: "Empty jerricans go back to a warehouse - <name> is not one".

**Data / sync impact**

- One row in `marking_sites` (id, code, name, site type, active).
- Audit entry `SITE_CREATE` or `SITE_UPDATE`. Outbox message `SITE`.
- Desktop: upserts code, name, site type and active. Sign-in lists only **active** sites. When sync is configured for one site,
  sign-in lists only that site.
- Transfer destinations on the desktop come from the same active-sites list.
- A deactivated site cannot sign in on its own desktop, so anything waiting to be received there cannot be received until the
  site is reactivated (see Review notes).

**Review notes**

1. **Where does an administrator get a site's id?** The desktop needs the site's id in its settings (`sync.site-id`), and the
   deployment notes say to copy it "from the web app". The Sites screen shows code, name, type and status, but **not the id**.
   The id is returned by the API but not displayed. Confirm where administrators are meant to find it.
2. **Site type is not checked against roles.** The code uses the site type in only two places: the mobile location rule at
   shift start, and the empties-to-warehouse rule. Nothing stops a warehouse manager from working at a FIXED site, or a
   supervisor from marking trucks at a WAREHOUSE site. Confirm whether that should be prevented.
3. **Deactivating a site strands its stock.** There is no check for open shifts, containers held, or transfers addressed to the
   site. A deactivated site cannot sign in, so transfers sent to it cannot be received at that desktop until it is reactivated.
4. **Changing the type takes effect silently.** There is no check for an open shift when a site's type changes. Confirm the
   intended behaviour.
5. **Codes and names are not normalised** (same as companies, see 7.1).
6. **Update requires the active flag** (same as companies, see 7.1).

**Code references**

- Backend: `backend/src/main/java/org/jai/fuelmarking/central/master/AdminController.java` (`/api/admin/sites`, `/sites/{id}/api-key`),
  `backend/src/main/java/org/jai/fuelmarking/central/master/MasterDataService.java` (`saveSite`, `sites`),
  `backend/src/main/java/org/jai/fuelmarking/central/master/ApiKeys.java` (`issue`, hashed storage),
  `backend/src/main/resources/db/migration/V1__central_schema.sql` (`marking_sites`, `site_credentials`).
- Web: `web/src/app/pages/admin/sites.component.ts` (form, outbox notice, API key notice).
- Desktop: `src/main/java/org/jai/fuelmarking/ui/LoginView.java` (site list, configured site only),
  `src/main/java/org/jai/fuelmarking/service/ShiftService.java` (`start`, mobile rule),
  `src/main/java/org/jai/fuelmarking/service/TransferService.java` (`returnEmpties`, warehouse rule),
  `src/main/java/org/jai/fuelmarking/dao/MasterDataDao.java` (`sites`, active only),
  `src/main/java/org/jai/fuelmarking/sync/MasterDataApplier.java` (`site`).
- Docs: `docs/deployment.md` and `docs/windows.md` (site id in the desktop settings).

---

## 7.3 Create container type

**Status**: Implemented. Container types are the kinds of container the warehouse handles: **bulk** (IBC, drum) and **jerrican**
(plastic, metal). The seeded list is IBC, Drum, Plastic jerrican and Metal jerrican.

**Where / who**

- Web: **Administration > Container types** (route `/admin/container-types`). Administrator only.
- Desktop: read for the bulk and jerrican drop-downs, and for how many seals a container carries. No desktop screen edits it.

**Preconditions**

- Signed in as an administrator.

**Behaviour**

- The list shows Code, Name, Kind (Bulk or Jerrican), Seals, Usual capacity (kg, or "varies") and Status.
- **New container type** opens a form with:
  - **Code**: required, up to 30 characters. Cannot be changed after creation.
  - **Name**: required, up to 100 characters.
  - **Kind**: Bulk container (IBC, drum ...) or Jerrican. Cannot be changed after creation. Choosing Jerrican sets seals to at least 1.
  - **Seals**: "None required" (0, offered for bulk only), 1 or 2. A new type starts as a jerrican with 1 seal.
  - **Usual capacity (kg, optional)**: a suggestion used when a container of this type is received. Blank means it varies.
    Stored in grams, rounded to 0.1 g.
- New types are created active. **Activate / Deactivate** toggles the flag.
- Seeded starting types:
  - `IBC` (bulk, no seals), `DRUM` (bulk, no seals).
  - `JERRICAN_PLASTIC` (jerrican, 1 seal, suggested 22 kg).
  - `JERRICAN_METAL` (jerrican, 2 seals, suggested 18 kg).

**Validation rules & error messages**

- Blank code or name: "code is required" or "name is required".
- Kind not Bulk or Jerrican: "category must be one of [BULK, JERRICAN]".
- Seals outside 0 to 2: "seals must be 0, 1 or 2".
- Jerrican with no seal: "a jerrican carries at least one seal".
- Capacity zero or below: "default capacity must be more than zero".
- Changing the code or kind of an existing type: "The code and category of a container type cannot be changed - add a new type instead."
- Duplicate code: HTTP 409, "Code, username or barcode already exists".
- Code is trimmed and upper-cased on save.

**Data / sync impact**

- One row in `container_types` (id, code, name, category, seals, default capacity in grams, active).
- Audit entry `CONTAINER_TYPE_CREATE` or `CONTAINER_TYPE_UPDATE` (detail: code, kind, seals, "(inactive)").
- Outbox message `CONTAINER_TYPE`. Desktop upserts the type.
- Jerricans registered at head office reference a container type. The desktop will not apply a jerrican until its type has
  arrived, and waits for it.

**Review notes**

1. **Seals can change on a type that is in use.** Only the code and kind are locked. Changing a type's seal count changes what
   the desktop asks for on every container of that type. Confirm whether seals should be locked too.
2. **Deactivated types and existing containers.** The desktop looks up container types with an active-only query. Confirm
   what happens to containers of a deactivated type on the desktop (for example, when they are opened or dispatched).
3. **Zero capacity is treated as blank.** On the web form, entering 0 kg is saved as "varies", not rejected, because 0 is
   read as empty. The API would refuse 0, so the form hides the error.

**Code references**

- Backend: `backend/src/main/java/org/jai/fuelmarking/central/master/MasterDataService.java` (`saveContainerType`, `containerTypes`),
  `AdminController.java` (`/api/admin/container-types`), `backend/src/main/resources/db/migration/V9__Container_types.sql` (table, seeds).
- Web: `web/src/app/pages/admin/container-types.component.ts` (form, kind and seal rules, kg to grams conversion).
- Desktop: `src/main/java/org/jai/fuelmarking/sync/MasterDataApplier.java` (`containerType`, `jerrican` dependency),
  `src/main/java/org/jai/fuelmarking/dao/MasterDataDao.java` (`containerTypes`, `containerType`, active only).
- Tests: `backend/src/test/java/org/jai/fuelmarking/central/MasterDataOutboxIT.java` (`containerTypesAreMasterDataWithSealsAndAUsualCapacity`).

---

## 7.4 Create rum type

**Status**: Implemented.

**Where / who**

- Web: **Administration > Rum types** (route `/admin/rum-types`). Administrator only.
- Desktop: rum types are read for drop-downs and the colour swatches. No desktop screen edits them.

**Preconditions**

- Signed in as an administrator.

**Behaviour**

- The list shows Code, Name, Colour (swatch and hex code) and Status, ordered by name.
- **New rum type** opens a form with:
  - **Code**: required, up to 30 characters.
  - **Name**: required, up to 100 characters.
  - **Colour**: a colour picker with a hex text box. Defaults to grey (`#808080`) on a new record.
- A rum type is created active. **Activate / Deactivate** toggles the flag.
- A rum type is used by fuel types (each fuel type is marked with one rum type), by registered jerricans (each holds one rum type),
  and by the desktop's colour swatches.
- A starting rum type, `STANDARD` (name "Standard"), exists from the start; the starting fuel types use it.

**Validation rules & error messages**

- Blank code or name: "code is required" or "name is required".
- Colour not in `#RRGGBB` form: "color must look like #RRGGBB". A blank colour is saved as grey.
- Duplicate code: HTTP 409, "Code, username or barcode already exists".
- Code is trimmed and upper-cased. Colour is stored in upper case.

**Data / sync impact**

- One row in `rum_types` (id, code, name, colour, active).
- Audit entry `RUM_TYPE_CREATE` or `RUM_TYPE_UPDATE`. Outbox message `RUM_TYPE`.
- Desktop upserts the rum type. A colour the desktop cannot read is replaced with grey, without a message.
- The desktop stores the rum type **code** on containers, markings and doses, and joins on it. This is what makes the code
  important (see Review notes).

**Review notes**

1. **Codes can change after creation.** Unlike container types, a rum type's code can be edited. The desktop keeps the rum code
   as text on many rows and joins on it, so a changed code would leave existing history and jerricans pointing at a code that no
   longer exists. Confirm whether the code should be locked once the type is in use.
2. **Inactive rum types can still be chosen by the API.** The web jerrican form offers only active rum types, but the server
   checks that the rum type exists, not that it is active. Confirm.
3. **Invalid colours fall back silently on the desktop**, to grey, while the web refuses them with a message.

**Code references**

- Backend: `backend/src/main/java/org/jai/fuelmarking/central/master/MasterDataService.java` (`saveRumType`, `COLOUR` pattern, `rumTypes`),
  `AdminController.java` (`/api/admin/rum-types`), backend migrations `V7__Fuel_rum_types_and_jerricans.sql` (table and the starting `STANDARD` rum type) and `V8__Rum_type_colour.sql` (colour).
- Web: `web/src/app/pages/admin/code-list.component.ts` (kind "rum": colour picker and text box).
- Desktop: `src/main/java/org/jai/fuelmarking/sync/MasterDataApplier.java` (`rumType`),
  `src/main/java/org/jai/fuelmarking/dao/MasterDataDao.java` (`rumTypes`, `rumColors`).

---

## 7.5 Create fuel type

**Status**: Implemented. A fuel type is what a compartment of a truck carries (Petrol, Diesel, Kerosene in the starting list). Each
fuel type is **marked with one rum type**, and that rum type sets the dose for the compartments that carry the fuel.

**Where / who**

- Web: **Administration > Fuel types** (route `/admin/fuel-types`). Administrator only.
- Desktop: fuel types are read for the compartment drop-downs. The rum type follows from the fuel type.

**Preconditions**

- Signed in as an administrator.
- At least one active rum type exists (a fuel type cannot be saved without one).

**Behaviour**

- The list shows Code, Name, Rum type (swatch and name) and Status, ordered by name.
- **New fuel type** opens a form with:
  - **Code**: required, up to 30 characters.
  - **Name**: required, up to 100 characters.
  - **Marked with rum type**: required. Only active rum types are offered. A rum type that has since been deactivated stays
    selectable only on the fuel type that already uses it.
- A fuel type is created active. **Activate / Deactivate** toggles the flag. Changing the rum type on an existing fuel type
  is allowed.
- Starting list: `PETROL`, `DIESEL`, `KEROSENE`, all marked with `STANDARD`.
- The rum dose itself is a desktop configuration setting, not a fuel type attribute. It is 10 grams per 1000 litres
  (`rum-dosage-per-1000l` in the desktop settings file), and it applies to every fuel.

**Validation rules & error messages**

- Blank code or name: "code is required" or "name is required".
- No rum type chosen: "Choose the rum type this fuel is marked with (rumTypeId)".
- Rum type unknown or inactive: "Choose an active rum type for this fuel".
- Duplicate code: HTTP 409, "Code, username or barcode already exists".
- Code is trimmed and upper-cased. Name is trimmed.

**Data / sync impact**

- One row in `fuel_types` (id, code, name, rum type id, active).
- Audit entry `FUEL_TYPE_CREATE` or `FUEL_TYPE_UPDATE` (detail "CODE -> RUMCODE", plus "(inactive)" when relevant).
- Outbox message `FUEL_TYPE`, which carries the rum type's code as well as its id.
- Desktop upserts the code, name and active flag. It keeps the rum code it already has if a message arrives without one.
- Existing trucks keep the rum type they were registered with, because the desktop stores the rum type on each compartment
  when the truck is registered. Changing the fuel's rum type affects trucks registered afterwards. Confirm this is the intent.

**Review notes**

1. **Codes can change after creation.** As for rum types (7.4), the desktop stores fuel codes as text on markings and
   compartments. A code change would break the link to history.
2. **One rum type per fuel, and one dose rate for all fuels.** The dose rate is a single site setting. Confirm that no fuel
   needs a different rate.
3. **Changing a fuel's rum type does not warn about trucks already registered** that will be dosed later. The system relies on
   each compartment keeping its own rum type. Confirm that is what the reviewer expects.

**Code references**

- Backend: `backend/src/main/java/org/jai/fuelmarking/central/master/MasterDataService.java` (`saveFuelType`, `fuelTypes`),
  `AdminController.java` (`/api/admin/fuel-types`), backend migrations `V7__Fuel_rum_types_and_jerricans.sql` (table, starting list),
  `V15__Fuel_type_rum_type.sql` (rum type per fuel).
- Web: `web/src/app/pages/admin/code-list.component.ts` (kind "fuel": rum type drop-down).
- Desktop: `src/main/java/org/jai/fuelmarking/sync/MasterDataApplier.java` (`fuelType`),
  `src/main/java/org/jai/fuelmarking/dao/MasterDataDao.java` (`fuelTypes`, `fuelType`),
  `src/main/resources/application.yml` (`rum-dosage-per-1000l`, the dose rate),
  `src/main/java/org/jai/fuelmarking/model/TruckMarking.java` (per-compartment dose required).

---

## 7.6 Create location

**Status**: Implemented. A location is a place a **mobile** site works from. A shift records where it was done.

**Where / who**

- Web: **Administration > Locations** (route `/admin/locations`). Administrator only.
- Desktop: a supervisor picks a location when starting a shift. A mobile site must pick one; a fixed site may.

**Preconditions**

- Signed in as an administrator.

**Behaviour**

- The list shows Code, Name and Status, ordered by name.
- **New location** opens a form with **Code** (required, up to 30 characters) and **Name** (required, up to 100 characters).
- A location is created active. **Activate / Deactivate** toggles the flag. Deactivated locations are no longer offered on the
  desktop.
- On the desktop, the location picker on the Shift screen reads "Where are you working? (required)" for a mobile site, and
  "Location (optional)" otherwise.
- The location's name is kept on each shift that uses it, so a later rename does not rewrite past shifts.
- The web app filters Markings and Shifts by location. The Dashboard's site table shows each site's most recent location.

**Validation rules & error messages**

- Blank code or name: "code is required" or "name is required".
- Duplicate code: HTTP 409, "Code, username or barcode already exists".
- Code is trimmed and upper-cased on save.
- Desktop, mobile site starting a shift with no location: "Choose where <site name> is working before starting the shift".
- Desktop, location retired between choosing it and starting the shift: "Unknown location <name> - head office may have retired it".

**Data / sync impact**

- One row in `locations` (id, code, name, active).
- Audit entry `LOCATION_CREATE` or `LOCATION_UPDATE`. Outbox message `LOCATION`.
- Desktop upserts the location. Shifts store the location's id and name.

**Review notes**

1. **Inactive items still appear in the web filters.** The web lists every location, company, site and fuel type, active or not,
   in its filter drop-downs (the lookups query has no active filter). Confirm whether filters should show only active items,
   with an "include inactive" option for finding past records.
2. **Fixed sites may choose a location.** The service comment says a fixed site "may" choose one. The screen shows the picker
   for every site, so a fixed site's shift can carry a location. Confirm this is wanted.
3. **Location is the only place-type master data.** There is no hierarchy above it (see 7.7).

**Code references**

- Backend: `backend/src/main/java/org/jai/fuelmarking/central/master/MasterDataService.java` (`saveLocation`, `locations`),
  `AdminController.java` (`/api/admin/locations`), migration `backend/src/main/resources/db/migration/V17__Locations.sql` (table,
  shift view with location), and the desktop `src/main/resources/db/migration/V27__Locations.sql`.
- Web: `web/src/app/pages/admin/code-list.component.ts` (kind "location"), `web/src/app/core/lookups.service.ts`.
- Desktop: `src/main/java/org/jai/fuelmarking/service/ShiftService.java` (`start`, mobile rule, retired-location message),
  `src/main/java/org/jai/fuelmarking/ui/ShiftTab.java` (location picker),
  `src/main/java/org/jai/fuelmarking/dao/MasterDataDao.java` (`locations`, active only),
  `src/main/java/org/jai/fuelmarking/sync/MasterDataApplier.java` (`location`).
- Tests: `backend/src/test/java/org/jai/fuelmarking/central/MasterDataOutboxIT.java` (`locationsAreMasterDataLikeTheRest`),
  `src/test/java/org/jai/fuelmarking/MobileSiteIT.java`.

---

## 7.7 Create administrative units

**Status**: **Not found in code.**

**What was searched.** The repository (desktop, backend, web, migrations, scripts and docs) was searched for: "administrative",
"admin unit", "district", "province", "region", "commune", "county", "ward", "municipal", "governorate", "wilaya", "zone" and
"sub-county". The only "zone" hits are time-zone references, and the only "region" hits are JavaFX's `Region` class and a CSS selector. There is no entity, table, API route,
screen or setting for administrative units.

**Closest things that exist**

- **Locations (7.6)**: a flat list of places for mobile sites. There is no parent, so it cannot express a region above a district.
- **Sites (7.2)**: a site has a code, name and type, and no parent or area.

**Where / who**: not applicable.

**Review notes**

1. **Confirm the meaning.** If "administrative units" means a hierarchy such as region, district and site, it is a new master-data
   type. It would need a table, admin screens, outbox and Kafka messages, and desktop applier support, following the pattern of
   7.6.
2. **Is it needed for a report or a filter?** If the need is a regional filter on the Dashboard, locations may be enough, or a
   parent field on locations may be the smallest change. This is a decision for the product owner.

**Code references**: none. The closest master-data pattern to copy is `MasterDataService.saveLocation` and
`MasterDataApplier.location`.
