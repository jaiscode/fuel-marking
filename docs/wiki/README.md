# Fuel Marking System - behaviour wiki

This wiki describes how the Fuel Marking System behaves today, for review. Every statement is read from the code, tests and
migrations in this repository as of 2026-10-30. It is not a product specification. Where the code is silent, the page says
"not found in code". Where the behaviour looks unintended or needs a decision, the page lists a **Review note**.

Read the **At a glance** table at the top of each page first, then the review notes.

**Status vocabulary**

- **Implemented**: the behaviour exists in the code and is covered by at least one test or a clear code path.
- **Partial**: part of the behaviour exists; the rest is not found in code. The page says which part.
- **Not found in code**: no code, table, route or screen for it. The page says what was searched and the closest existing thing.
- **see page**: the feature is covered on another page of this wiki (used in the master coverage table below).

---

## System overview

The Fuel Marking System tracks rum from the warehouse, through jerricans and intermediate bulk containers (IBCs), into the fuel
trucks that are marked with it, and gives head office a live picture of it all. It has three applications. The **marking site
desktop app** (JavaFX, with a local PostgreSQL database) runs at every marking site and warehouse. It works offline, and it is where
people sign in with a site, their name and a PIN to open shifts, register and dose trucks, dispense and verify, move containers,
and run site reports. The **central backend** (Spring Boot, with a central PostgreSQL database) receives what every site pushes,
holds the master data (sites, oil companies, fuel and rum types, container types, locations, registered jerricans and users), and
serves the **web app** (Angular), which head office uses for its dashboard, lists and administration. Synchronisation runs in both
directions over HTTPS: each desktop pushes its records to the backend with its site's API key and pulls the transfers addressed to
it. Master data flows the other way through a compacted Kafka topic, so every site receives the latest copy, and a site that was
offline catches up when it next runs.

---

## Glossary

| Term | Meaning in this system |
|---|---|
| **Rum** | The product added to fuel at marking. Every rum quantity is in **grams**; column names ending in `_ml` are historical. |
| **Rum type** | A kind of rum, with a code, a name and a colour. A fuel type is marked with one rum type. The starting type is `STANDARD`. |
| **Dose** | The amount of rum required for a compartment: its litres divided by 1000, times the configured rate (10 g per 1000 L). The **weighed dose** is what was actually measured on the scale. Doses are checked against a 2 % tolerance. |
| **Fuel type** | What a truck compartment carries (starting list: Petrol, Diesel, Kerosene). Each is marked with one rum type. |
| **OMC (oil marketing company)** | The company a truck belongs to. Stored as a name on each truck, and as an id. The web app calls them "oil companies". |
| **IBC** | Intermediate bulk container, a large bulk container for rum. Container category **BULK**. |
| **Drum** | A bulk container, the same category as an IBC. Seeded as its own container type. |
| **Jerrican** | A small container for rum, sealed, with a container type that sets its seals: plastic (1 seal) or metal (2 seals). The web app suggests a usual capacity of 22 kg (plastic) and 18 kg (metal). Category **JERRICAN**. |
| **Container type** | The kind of a container: bulk (IBC, drum) or jerrican (plastic, metal). Sets how many seals it carries and its usual capacity. |
| **Registered jerrican** | A jerrican head office has registered by barcode, with its container type, capacity and rum type. The warehouse can only receive a jerrican that is on this list. |
| **Container status** | SEALED (full, sealed), OPENED (seals broken, rum being drawn), IN_TRANSIT (dispatched, not yet received), EMPTY (no rum left), MISSING (not found at reconciliation). |
| **Seal** | A numbered seal on a container: a top seal and a bottom seal, recorded on the container. A seal check at reconciliation records FOUND, MISSING or SEAL_BROKEN. |
| **Marking site** | A site where trucks are marked. Its type is FIXED (in one place) or MOBILE (moves; must name a location at shift start). |
| **Warehouse** | A site of type WAREHOUSE. It holds IBCs, drums and jerricans, supplies marking sites, and receives empty jerricans back. |
| **Site type** | FIXED, MOBILE or WAREHOUSE. Only some rules depend on it (see 7.2). |
| **Location** | A place a mobile site works from. A shift records which location it was done at. |
| **Shift** | A supervisor's working period at one site, from opening to closing. Opening and closing rum in the opened jerricans are recorded, and their difference is the variance. |
| **Variance** | Closing rum minus expected rum. A non-zero variance means rum is unaccounted for. |
| **Compartment** | A section of a truck tank, with its own capacity, its own fuel type and therefore its own rum type and dose. |
| **Declared volume** | The litres declared for a truck when it is registered. |
| **Marking (truck marking)** | The record of one truck being marked. Its statuses run REGISTERED, then DOSED, DISPENSED, COMPLETED. DELETED is for a truck registered by mistake and deleted before its first dose. |
| **Register / dose / dispense / verify** | The four steps of a marking: register the truck; dose the compartments with rum; dispense the fuel; verify, with a signature, to complete. |
| **Order number / trailer or wagon** | The order the truck was booked under, and the trailer or wagon number if it has one. |
| **Supply** | A transfer of sealed containers from a warehouse to a site. Dispatched by the warehouse manager. |
| **Empties back ("RETURN")** | A transfer of empty jerricans from a marking site back to a warehouse, for refilling. Dispatched by a supervisor. |
| **In transit** | A transfer dispatched but not yet fully received. The web app shows the transfer as "In transit" (status DISPATCHED) until every container has been received. |
| **Stock count** | A signed count of rum. Kind **WAREHOUSE**: IBCs and drums at a warehouse. Kind **SHIFT**: the opened jerricans at a shift's reconciliation. |
| **Reconciliation** | Comparing counted rum with what the system holds, at shift close or at a warehouse count. A sealed jerrican is checked by its seal. |
| **Incident** | A recorded loss or spill: type SPILLAGE, LEAKAGE or OTHER, with the rum lost in grams and the rum type. |
| **Master data** | Shared reference records owned by head office: sites, oil companies, fuel types, rum types, container types, locations, registered jerricans and users. Sites receive them and cannot change them. |
| **Outbox** | A table on the central side in which each master-data change waits, in the same transaction as the change, until it is sent to Kafka. The desktop has its own outbox for records it sends to central. |
| **Kafka master-data topic** | The compacted topic (`fuelmarking.master-data` by default) that carries master data from head office to every site. |
| **Sync push / pull** | Push: a desktop sends its records to the backend. Pull: a desktop asks for the transfers addressed to it. Both use the site's API key. |
| **API key** | A per-site secret, issued in the web app and shown once. Only its hash is stored. It is what the desktop uses to push and pull. It is never written in these pages. |
| **PIN** | A 4 to 8 digit number set by an administrator for a desktop user. The desktop checks it against a stored hash, so sign-in works offline. |
| **Lockout** | After 5 wrong PINs, the user is locked for 5 minutes. A PIN reset clears the lockout. |
| **Administration** | The web app's administrator-only screens (sites, companies, types, locations, jerricans, users). |

---

## Roles and sign-in

There are two different kinds of role. The **desktop roles** are the job a person does at a site. The **web roles** decide
who can open the web app and its Administration screens.

### Desktop (marking site app)

Sign-in is a site, a user and a PIN:

1. Choose a **marking site**. If the desktop is configured for one site, only that site is offered.
2. Choose a **user**. The list shows the users assigned to that site, plus users assigned to any site.
3. Enter the **PIN** and sign in. The check runs locally against the stored hash, so it works offline.

Sign-in messages (as shown on the desktop):

- "Choose a site and a user", "Enter your PIN".
- "Unknown user".
- "No PIN has been set for this user. Ask an administrator."
- "Incorrect PIN (N attempt(s) left)."
- "Too many wrong PINs. This user is locked for 5 minutes." Then "Too many wrong PINs. Try again at HH:MM." until the lock lifts.
- "No marking sites yet. Master data is created in the web app and synced here." when no site has arrived.

The tabs each desktop role sees (from the main screen code; the shift gate applies to supervisors):

| Desktop role | Screens | Notes |
|---|---|---|
| **Supervisor** | Shift, Reconcile stock, jerricans (open, close, declare empty, transfers), Register truck, Verify, Incidents, Inventory, Reports | Until a shift is started, only Shift and Reports are open. Starts and closes shifts. |
| **Dosing operator** | Dose, Incidents, Inventory | Not gated by the shift rule (only supervisors are). |
| **Dispense operator** | Dispense, Incidents | Not gated by the shift rule (only supervisors are). |
| **Warehouse manager** | Receive jerricans, Refill, Transfers (send supplies, receive empties), Receive bulk, Stock counts, Inventory, Incidents, Reports | Does not work in shifts. Dispatches supplies. Receives returned jerricans. |

### Web app

- Sign-in is through the organisation's identity provider (OpenID Connect, authorization code with PKCE). No client secret is held
  in the browser. The web app is served by the central backend.
- Two roles come from the token's roles claim. The claim names are configurable; the defaults are:
  - **Viewer** (`fuelmarking-viewer`): the Dashboard, Markings, Inventory, Incidents, Shifts, Stock counts and Transfers.
  - **Administrator** (`fuelmarking-admin`): everything a viewer can see, plus the Administration screens.
- A person with neither role sees "No access". The API enforces the same rules.
- The audit log is available through the administrator API only. No web screen shows it.

---

## Master coverage table

Every module in the plan, with its page. Status is given for the features documented on this page; other pages will be
filled in by the orchestrator.

| Ref | Feature | Page | Status |
|---|---|---|---|
| 1.1 | Open shift | [01-shift-management.md](01-shift-management.md) | see page |
| 1.2 | Reopen shift | [01-shift-management.md](01-shift-management.md) | see page |
| 1.3 | Close shift | [01-shift-management.md](01-shift-management.md) | see page |
| 1.3.1 | Shift summary | [01-shift-management.md](01-shift-management.md) | see page |
| 2.1.1 - 2.1.10 | Jerrican management (items 2.1.1 to 2.1.10) | [02-container-management.md](02-container-management.md) | see page |
| 2.2.1 - 2.2.4 | IBC management (items 2.2.1 to 2.2.4) | [02-container-management.md](02-container-management.md) | see page |
| 3.1 | Register truck | [03-marking.md](03-marking.md) | see page |
| 3.1.1 | Compartments | [03-marking.md](03-marking.md) | see page |
| 3.2 | Dosing | [03-marking.md](03-marking.md) | see page |
| 3.2.1 | Dose cancellation | [03-marking.md](03-marking.md) | see page |
| 3.3 | Dispensing | [03-marking.md](03-marking.md) | see page |
| 3.4 | Verify | [03-marking.md](03-marking.md) | see page |
| 4.1 | IBC (warehouse reconciliation) | [04-reconciliation.md](04-reconciliation.md) | see page |
| 4.2 | Jerrican at warehouse (reconciliation) | [04-reconciliation.md](04-reconciliation.md) | see page |
| 5.1 | Marking summary report (marking site) | [05-reports.md](05-reports.md) | **Implemented** |
| 5.2 | Inventory report (warehouse and marking site) | [05-reports.md](05-reports.md) | **Implemented** |
| 5.3 | Movement summary report (warehouse and marking site) | [05-reports.md](05-reports.md) | **Not found as a summary**. Partial: transfer lists (desktop Reports "Transfers", web Transfers page) |
| 5.4 | Shift summary report (marking site) | [05-reports.md](05-reports.md) | **Not found as a report**. Partial: web Shifts list; desktop shift screen (see 1.3.1) |
| 6.1 | Incident management (item 6.1) | [06-incident-management.md](06-incident-management.md) | see page |
| 6.2 | Incident management (item 6.2) | [06-incident-management.md](06-incident-management.md) | see page |
| 7.1 | Create OMC (oil marketing company) | [07-master-data.md](07-master-data.md#71-create-omc-oil-marketing-company) | **Implemented** |
| 7.2 | Create marking site / warehouse | [07-master-data.md](07-master-data.md#72-create-marking-site--warehouse) | **Implemented** |
| 7.3 | Create container type | [07-master-data.md](07-master-data.md#73-create-container-type) | **Implemented** |
| 7.4 | Create rum type | [07-master-data.md](07-master-data.md#74-create-rum-type) | **Implemented** |
| 7.5 | Create fuel type | [07-master-data.md](07-master-data.md#75-create-fuel-type) | **Implemented** |
| 7.6 | Create location | [07-master-data.md](07-master-data.md#76-create-location) | **Implemented** |
| 7.7 | Create administrative units | [07-master-data.md](07-master-data.md#77-create-administrative-units) | **Not found in code** |
| 8.1 | QC sample | [08-others.md](08-others.md) | **Not found in code** |

---

## Review focus

The decisions and gaps most likely to need the reviewer's attention. Each is detailed on the page named.

1. **User PIN hashes reach every site.** The user message, with its PIN hash, is readable by every marking site, not only the
   user's own site. ([07-master-data.md](07-master-data.md), overview.)
2. **No movement or shift summary report.** Transfers and shifts are shown as lists only. Decide whether a summary is wanted.
   ([05-reports.md](05-reports.md), 5.3 and 5.4.)
3. **Rum-dosed figures are defined two ways.** The by-fuel report counts only weighed doses; the by-company report and the
   Dashboard fall back to the required dose. ([05-reports.md](05-reports.md), 5.1.)
4. **Codes can change after use** for fuel types and rum types, which the desktop joins on as text. Container type codes are
   locked; these are not. ([07-master-data.md](07-master-data.md), 7.4 and 7.5.)
5. **Where does a site's id come from?** The desktop needs it, the docs say to copy it from the web app, but the Sites screen
   does not display it. ([07-master-data.md](07-master-data.md), 7.2.)
6. **Deactivating a site strands its transfers.** A deactivated site cannot sign in, so transfers sent to it cannot be received
   there. ([07-master-data.md](07-master-data.md), 7.2.)
7. **Administrative units and QC samples are not in the system.** Confirm whether they are required, and what they mean.
   ([07-master-data.md](07-master-data.md), 7.7; [08-others.md](08-others.md), 8.1.)
8. **Audit log has no screen.** Administrators need the API to read it. ([07-master-data.md](07-master-data.md), roles and audit.)

---

## Conventions used in these pages

- Error messages are quoted exactly as the code writes them. Where the web app shows an API error, it shows the server's text in a
  notification.
- Code references are repository-relative paths with class or method names. They are not line numbers.
- No credentials, PINs, API keys, host names or addresses appear anywhere in this wiki. Where a value would be needed, it is
  described rather than written out.
- Diagrams are not used in this wiki; the behaviour is described in text and tables.
