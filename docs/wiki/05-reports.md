# 5. Reports

Behaviour wiki, module 5. Describes what the Fuel Marking System does today, as read from the code. Where the code and the
intent disagree, or where a behaviour looks unintended, it is listed under **Review notes**.

Two places show reports:

- **Desktop (marking site app)**: the **Reports** screen. It runs one report at a time for the site the person signed in at,
  and can export the result as CSV. It works offline, from the local database.
- **Web app**: the **Dashboard** (aggregates for all sites, or one) and the list screens (Markings, Inventory, Shifts, Transfers).
  It reads the central database, so it shows what each site has synced.

## At a glance

| Ref | Feature | Status | Where | Who | Data source |
|---|---|---|---|---|---|
| 5.1 | Marking summary report (marking site) | Implemented | Desktop Reports (3 kinds); web Dashboard tables and KPIs | Desktop: supervisor, warehouse manager. Web: viewer or admin | Desktop: local DB. Web: central `v_truck_markings` |
| 5.2 | Inventory report (warehouse and marking site) | Implemented | Desktop Reports "Inventory" (plus the Inventory screen); web Inventory page | Desktop: supervisor, warehouse manager. Web: viewer or admin | Desktop: local `containers`. Web: central `v_containers` |
| 5.3 | Movement summary report (warehouse and marking site) | **Not found as a summary.** Partial: row-level transfer lists | Desktop Reports "Transfers"; web Transfers page | Desktop: supervisor, warehouse manager. Web: viewer or admin | Desktop: `transfers`, `transfer_items`. Web: `v_transfers` |
| 5.4 | Shift summary report (marking site) | **Not found as a report.** Partial: shift list (web) and shift screen (desktop) | Web Shifts page; desktop Shift screen (owned by 1.3.1) | Web: viewer or admin. Desktop: supervisor (shift screen only) | Desktop: `shifts`. Web: `v_shifts` |

Common to all reports on the desktop:

- No totals row, anywhere. Totals appear only as the web Dashboard's KPI tiles.
- Dates are inclusive and are applied to the day of registration, dispatch or occurrence, from the start of FROM to the end of TO.
- Rum quantities are grams. Column names that end in `_ml` are a historical naming choice; the values are grams (migrations
  `V10__Rum_units_are_grams.sql` and the desktop `V9`/`V10` comments).

---

## 5.1 Marking summary report (marking site)

**Status**: Implemented. The desktop has three report kinds for this (by fuel type, by oil marketing company, and the per-truck
detail). The web app shows the fuel and company summaries on the Dashboard. The per-truck list is the Markings page, which is
documented in the marking module (see [03-marking.md](03-marking.md)).

**Where / who**

- Desktop: **Reports** screen, selected as "Marking summary - by fuel type", "Marking summary - by oil marketing company" or
  "Marking detail (per truck)". Shown to **supervisor** and **warehouse manager** only. A supervisor who has not started a
  shift can still open Reports.
- Web: **Dashboard** (route `/`) and **Markings** (route `/markings`). Any signed-in user with the viewer or admin role.

**Preconditions**

- Desktop: signed in. The report always covers the site the person signed in at. There is no site picker on the desktop.
- Web: the site must have synced its markings. A site that has not synced for over an hour is flagged on the Dashboard
  ("Site synchronisation" table).

**Behaviour**

*Desktop report controls*: REPORT (drop-down), FROM (defaults to the first day of the current month), TO (defaults to today),
Run (the default button), and Export CSV. The caption reads "<report name> - <site name> - <n> row(s)". An empty result shows
"No data for this selection".

| Report | Rows | Columns | Notes |
|---|---|---|---|
| Marking summary - by fuel type | One row per fuel type, taken from the compartments. A truck with two fuels counts under both, each with its own litres and the rum weighed for its compartments. | Fuel type, Trucks, Completed, Fuel marked (L), Rum dosed (g) | Trucks = distinct trucks. Completed = trucks in status COMPLETED. Rum dosed = weighed doses for the compartment. A truck with no weighed dose contributes 0. |
| Marking summary - by oil marketing company | One row per company name as stored on the truck. | Oil marketing company, Trucks, Completed, Fuel marked (L), Rum dosed (g) | Fuel marked = declared volume of the whole truck. Rum dosed = the weighed truck dose, or the required dose when nothing was weighed. Ordered by company name. |
| Marking detail (per truck) | One row per truck. | Order, Registered, Truck, Trailer / wagon, OMC, Fuel, Volume (L), Rum type, Rum required (g), Rum dosed (g), Dose source, Status, Registered by, Dosed by, Dispensed by, Verified by | Ordered by registration time. Status values shown raw (REGISTERED, DOSED, DISPENSED, COMPLETED). |

All three exclude trucks with status DELETED. They include trucks in every other status, so "Trucks" is larger than "Completed"
until the trucks are verified.

*Web Dashboard*:

- Filters: Site (All sites, or one site) and Period (Today, Last 7 days, Last 30 days; default Last 7 days). The period runs
  to today.
- Key figures (tiles):
  - **Trucks marked**: count of non-deleted markings in the period. Subtitle: count of COMPLETED ("verified").
  - **Fuel marked**: sum of declared volume, in litres. Subtitle: "declared volume".
  - **Rum dosed**: sum of the rum figure per truck, in grams. Subtitle: grams per 1000 L, computed as rum divided by
    (litres / 1000), shown as "–" when there are no litres.
  - **Incidents**: count, and grams lost (the incident module, see [06-incident-management.md](06-incident-management.md)).
  - **Open shifts**: every open shift at the chosen site(s). Not limited to the period. Subtitle: sites with markings in the period.
- Chart: "Rum dosed per day (g)". Ranges up to 92 days show every day, with zero for days without markings. Nothing is drawn
  when every day is zero ("No marking in this period.").
- "By fuel type" table: Fuel, Trucks, Fuel (L), Rum (g).
- "By oil marketing company" table: Company, Trucks, Fuel (L), Rum (g).
- The "Site synchronisation" table is always for all sites and ignores the filters.

*Web Markings list* (the per-truck list, a sibling of the detail report):

- Filters: Search (order number, truck number, or trailer number; "contains", case-insensitive), Site, Location, Oil company,
  Fuel, Status (REGISTERED, DOSED, DISPENSED, COMPLETED, DELETED), From, To, and Reset.
- Default From is 29 days before today, so the list covers 30 days including today. To is blank, which means no upper limit.
- DELETED markings appear only when that status is chosen.
- Columns: Registered, Order, Truck, Trailer / wagon, Site, Location, Oil company, Fuel, Volume (L), Rum (g), Status.
- 25 rows per page, newest first.

**Validation rules & error messages**

- Desktop, FROM or TO missing, or TO before FROM: "Choose a valid date range" (shown in an error dialog).
- Desktop, Export CSV before any run: "Run a report first".
- Web, Dashboard and Markings: no client-side check that TO is on or after FROM. An inverted range returns no rows and no message.
- The API rejects an unknown `groupBy` with HTTP 400: "groupBy must be one of [day, fuel, omc, site]". The web app only sends
  `fuel` and `omc`.

**Data / sync impact**

- Desktop reports are read-only, local, and independent of sync. They show the site's own data at the moment of the run.
- Web figures are only as current as the last sync from each site. A correction made on the desktop changes the web figures
  only after it syncs. Historical numbers can therefore move.
- Oil marketing company grouping is by the company **name text** stored on each truck, not by its id. See 7.1.
- Desktop CSV export: UTF-8 with a byte-order mark so Excel reads accents correctly; all cells quoted; a leading `=`, `+`, `-`
  or `@` is neutralised with an apostrophe (plain numbers are left alone). The file name is the report name in lower case
  plus the date (for example `marking_by_fuel-<date>.csv`).

**Review notes**

1. **Two "rum dosed" definitions.** The by-fuel report counts only weighed doses (0 when a truck was not weighed). The
   by-company report and the Dashboard fall back to the required dose. Both are labelled "Rum dosed (g)". Confirm which one
   the reviewer wants and label accordingly.
2. **Fuel rows do not add up to the truck count.** A two-fuel truck appears under both fuels, so the Trucks column summed
   over fuels is larger than the number of trucks. This is intended by the code comment. A reviewer may still want a total
   line or a note.
3. **Unit wording on the Dashboard chart.** The chart's accessible label says "in millilitres". The heading and the values are
   grams. The label is wrong.
4. **"Open shifts" is not period-filtered.** It counts all open shifts for the selected site(s), while the other tiles follow
   the period. The subtitle reads "site(s) marked in period", which is a different measure.
5. **Desktop reports are per site only.** Nothing on the desktop shows several sites together. The web app is the only
   consolidated view.
6. **No totals row** in any report or table, desktop or web.
7. **Completed excludes DISPENSED trucks.** A truck that is dispensed but not verified is counted in Trucks but not in
   Completed. This matches the status values, but the column name alone does not say it.
8. **Markings search label.** The web input says "Order or truck no." but the search also matches trailer numbers.
9. **Desktop reports have no role check in the service.** Only the Reports screen is limited to supervisor and warehouse
   manager. The screen is the only caller today.
10. **Web date range is not validated.** See Validation rules above.

**Code references**

- Desktop: `src/main/java/org/jai/fuelmarking/ui/ReportsTab.java` (screen, run, export),
  `src/main/java/org/jai/fuelmarking/service/ReportService.java` (`run`, `exportCsv`, date check),
  `src/main/java/org/jai/fuelmarking/dao/ReportDao.java` (`markingByFuel`, `markingByOmc`, `markingDetail`),
  `src/main/java/org/jai/fuelmarking/ui/MainView.java` (which roles see Reports).
- Web: `web/src/app/pages/dashboard.component.ts` (`load`, KPIs, chart, tables),
  `web/src/app/pages/markings.component.ts`.
- Central read API: `backend/src/main/java/org/jai/fuelmarking/central/web/ReadController.java` (`/api/web/overview`,
  `/api/web/markings/summary`, `/api/web/markings`), `backend/src/main/java/org/jai/fuelmarking/central/web/ReadService.java`
  (`overview`, `markingSummary`, `markings`), migration `backend/src/main/resources/db/migration/V11__Trailer_number.sql` and
  later `v_truck_markings` definitions (`V15`, `V21`, `V22`).
- Tests: `src/test/java/org/jai/fuelmarking/TransferReportIT.java` (reports reflect activity; CSV BOM and formula
  neutralising), `backend/src/test/java/org/jai/fuelmarking/central/ReadApiIT.java` (`summariesGroupCorrectly`,
  `aDeletedMarkingCountsNowhereButCanStillBeLookedUp`, `aTruckWithTwoFuelsCountsUnderBothAndShowsItsCompartmentsAndDoses`).

---

## 5.2 Inventory report (warehouse and marking site)

**Status**: Implemented. The desktop has an Inventory **report** (summary by container type and status) and an Inventory
**screen** (one row per container). The web app has an Inventory page with both a summary and a container list.

**Where / who**

- Desktop Reports, kind "Inventory": **supervisor** and **warehouse manager**.
- Desktop Inventory screen: **supervisor**, **dosing operator** and **warehouse manager**.
- Web Inventory page (route `/inventory`): any viewer or admin.

**Preconditions**

- Containers have been recorded at the site (by the warehouse receiving steps, see [02-container-management.md](02-container-management.md)).
- Web: the site has synced its containers.

**Behaviour**

*Desktop Inventory report*:

- The report is a point-in-time view of the site's containers. **The date range is ignored**, but the date pickers must still
  hold a valid range or the run is refused (see Review notes).
- Grouped by container type and status. Columns: Container type, Status, Containers (count), Rum (kg), Capacity (kg).
- Rum (kg) is the rum currently in the container; Capacity (kg) is the container's capacity. Values are grams divided by 1000.
- Statuses seen in the data: SEALED, OPENED, IN_TRANSIT, EMPTY, MISSING.
- No totals row.

*Desktop Inventory screen* (not a report, same data, one row per container):

- Refresh button. Three tiles: **IN IBCS / DRUMS** (bulk rum held at this site, kg), **IN JERRICANS** (non-bulk rum, sealed or
  opened, kg), and **CONTAINERS** (count of rows in the list).
- Table columns: Barcode, Type, Status (coloured pill), Seals (top / bottom, or a dash), Rum type (colour swatch), Volume (kg),
  Capacity (kg). Ordered by container type, then barcode.

*Web Inventory page*:

- Site drop-down (All sites or one). It filters both tables.
- **Summary** table: Site, Container, Status, Count, Rum (kg). Grouped by site, container type and status. Shows
  "No containers reported yet." when empty. No capacity column.
- **Containers** table: Barcode, Site, Type, Status, Seals, Rum (kg), Capacity (kg). Filters: Barcode or seal number (contains,
  case-insensitive, matches barcode, top seal or bottom seal), Type (container types), Status (SEALED, OPENED, IN_TRANSIT, EMPTY,
  MISSING). 25 rows per page. "No containers match." when empty.

**Validation rules & error messages**

- Desktop report: "Choose a valid date range" when the dates are missing or inverted, even though inventory ignores them.
- Desktop screen: no validation.
- Web: no validation. Search by seal or barcode returns no rows without a message.

**Data / sync impact**

- Desktop: `containers` for the site, live.
- Web: central `v_containers`, built from the containers each site synced. This is the last state each site reported, not a live
  count.
- A container in transit stays with the **sending** site, with status IN_TRANSIT, until it is received. The transfer sets its
  site to the sender while it travels. Rum in transit is therefore counted in the sender's inventory and not in the
  receiver's, on both the desktop and the web.
- A container marked MISSING keeps its site and has volume 0. It is counted in the number of containers.

**Review notes**

1. **Dates on a date-less report.** The Inventory report ignores FROM and TO but still refuses an inverted or missing range.
   A user may think the dates filter the result. Consider hiding the dates for this kind.
2. **In-transit rum counts at the sender.** Confirm that a site's inventory should include rum it has dispatched but that the
   receiver has not yet taken in. The summary and the desktop tiles both include it.
3. **MISSING containers are counted.** They add to the CONTAINERS tile and to the container count, with zero rum. Confirm
   whether they should be shown in the same list as held containers.
4. **Desktop tiles include IN_TRANSIT and MISSING rows** in the "IN JERRICANS" and "IN IBCS / DRUMS" sums where they are
   non-bulk or bulk. The tile subtitles do not mention it.
5. **Web summary has no capacity column.** The Containers table does. A reviewer comparing the two may expect a match.
6. **Freshness.** Web inventory can lag the desktop by the time between syncs. The Dashboard flags sites that are more than an
   hour behind, but the Inventory page does not show its own "as of" time.

**Code references**

- Desktop: `src/main/java/org/jai/fuelmarking/dao/ReportDao.java` (`inventory`),
  `src/main/java/org/jai/fuelmarking/ui/InventoryTab.java` (screen, tiles), `src/main/java/org/jai/fuelmarking/service/ContainerService.java`
  (`inventory`), `src/main/java/org/jai/fuelmarking/service/TransferService.java` (sets IN_TRANSIT and the sender site),
  `src/main/java/org/jai/fuelmarking/dao/ContainerDao.java` (`listBySite`, `setLocation`, MISSING update).
- Web: `web/src/app/pages/inventory.component.ts`, `web/src/app/shared/format.ts` (`fmtKg`).
- Central: `backend/src/main/java/org/jai/fuelmarking/central/web/ReadService.java` (`containers`, `inventory`),
  migration `backend/src/main/resources/db/migration/V14__Container_seals_view.sql` (`v_containers`).
- Tests: `src/test/java/org/jai/fuelmarking/TransferReportIT.java` (`reportsReflectActivity`, Inventory headers),
  `backend/src/test/java/org/jai/fuelmarking/central/ReadApiIT.java` (`inventoryIncidentsShiftsAndTransfers`).

---

## 5.3 Movement summary report (warehouse and marking site)

**Status**: **Not found as a summary.** The closest things are row-level lists of transfers: the desktop **Transfers** report and
the web **Transfers** page. Neither gives counts or totals by route, product or period. No code, table or screen named
"movement" exists.

**Where / who**

- Desktop Reports, kind "Transfers": **supervisor** and **warehouse manager**.
- Web Transfers page (route `/transfers`): any viewer or admin.

**Who moves what** (the transfer rules in the code, for reference):

- A **supply** is dispatched by the **warehouse manager**, from a warehouse to a site. Only SEALED containers can go.
- An **empties return** ("Empties back") is dispatched by a **supervisor** from a marking site to a warehouse. Only EMPTY jerricans
  at that site can go, and the destination must be a WAREHOUSE site.
- The destination receives the containers. A supply is received by a supervisor or the warehouse manager. A supervisor receiving
  must have a shift open at the destination (the transfer test expects "Start a shift first"). A returned jerrican is received
  by the warehouse manager.

**Preconditions**

- Transfers have been dispatched and, for the web, synced.

**Behaviour**

*Desktop Transfers report*:

- Filter: a transfer is included when its dispatch date is in range and this site is either the sender or the receiver.
- One row **per container** per transfer.
- Columns: Dispatched, From, To, Status, Container, Type, Received.
  - Status is the raw transfer status: DISPATCHED (in transit) or RECEIVED (every container received).
  - Type is the container type code.
  - Received is the time that container was received, blank until then.
- Ordered by dispatch time, then barcode. No totals.

*Web Transfers page*:

- Filters: Site (sender or receiver), Status (In transit = DISPATCHED; Received = RECEIVED), From, To. Default From is 89 days
  before today (90 days including today).
- Columns: Dispatched, Kind ("Supplies" or "Empties back"), From, To, Status (badge), Containers (count), Received ("x of y").
- Clicking a row opens the container list for that transfer, each marked "received" or "in transit".
- 25 rows per page. No totals.

**Validation rules & error messages**

- Desktop: "Choose a valid date range" for a missing or inverted range.
- Web: no validation. An inverted range returns no rows.

**Data / sync impact**

- Desktop: local `transfers`, `transfer_items` and `containers`. A transfer reaches the other site through the sync pull, so
  the receiving desktop sees it after its next sync.
- Web: central `v_transfers`, from the transfers each site pushed. A transfer appears on the web once either end has synced.
- The transfer status becomes RECEIVED only when **all** its containers are received. A partial receipt leaves the transfer
  as DISPATCHED, which the web labels "In transit", even though some containers have arrived.

**Review notes**

1. **Is a movement summary wanted?** Neither app counts movements. Decide whether the report should show counts by route,
   container type and period, or whether the row lists are enough.
2. **Two labels for one status.** The desktop report shows DISPATCHED and RECEIVED; the web shows "In transit" and "Received".
   Align them.
3. **Rows are containers, not transfers.** A 50-container transfer gives 50 desktop rows with the same dispatch time and status.
4. **Partial receipt.** A transfer with 1 of 2 containers received is still "In transit" on the web and DISPATCHED on the
   desktop. The Received column is the only signal.
5. **Default period differs.** Transfers default to 90 days; markings and shifts default to 30. Confirm that is intended.
6. **Transfers from before returns existed.** Their kind is read as SUPPLY (the web transfer detail says so). Confirm that
   no reporting needs to tell "empties back" apart from supplies on the desktop report, which does not show the kind at all.

**Code references**

- Desktop: `src/main/java/org/jai/fuelmarking/dao/ReportDao.java` (`transfers`),
  `src/main/java/org/jai/fuelmarking/service/TransferService.java` (`dispatch`, `returnEmpties`, `receive`, role guards),
  `src/main/java/org/jai/fuelmarking/ui/TransferTab.java` (who sees which mode, destinations),
  `src/main/java/org/jai/fuelmarking/sync/InboundApplier.java` (status updates from sync).
- Web: `web/src/app/pages/transfers.component.ts`.
- Central: `backend/src/main/java/org/jai/fuelmarking/central/web/ReadService.java` (`transfers`, `transfer`),
  migration `backend/src/main/resources/db/migration/V13__Transfer_kind.sql` (`v_transfers`).
- Tests: `src/test/java/org/jai/fuelmarking/TransferReportIT.java` (`dispatchAndReceiveWithoutWeighing`, `reportsReflectActivity`),
  `backend/src/test/java/org/jai/fuelmarking/central/ReadApiIT.java` (`inventoryIncidentsShiftsAndTransfers`,
  `aTransferOfEmptiesBackToTheWarehouseIsToldApartFromSupplies`).

---

## 5.4 Shift summary report (marking site)

**Status**: **Not found as a report** on either app. The desktop Reports screen has no shift kind. The web **Shifts** page is a
list of shifts with their opening rum, closing rum and variance; it is not a summary of what happened in each shift. The
desktop shift screen has the per-shift rum reconciliation. That screen belongs to [01-shift-management.md](01-shift-management.md)
(item 1.3.1), so only the report-side gap is described here.

**Where / who**

- Web Shifts page (route `/shifts`): any viewer or admin.
- Desktop: the Shift screen, for the **supervisor**. Not in Reports.
- Web Dashboard: "Open shifts" tile and the "Shift" column of the site synchronisation table.

**Preconditions**

- Shifts have been opened and synced to the web.

**Behaviour**

*Web Shifts page*:

- Filters: Site, Location, Status (Open or Closed), From, To. Default From is 29 days before today (30 days including today).
- Columns: Site, Location, Supervisor, Started, Ended, Status, Opening (g), Closing (g), Variance (g).
- Variance shows "–" while the shift is open. When closed, it shows a green "0" when the absolute variance is below 0.05 g, and
  a red figure otherwise.
- Description on the page: "Shift reconciliation of rum in opened jerricans. A non-zero variance means rum is unaccounted for."
- 25 rows per page, newest first. Empty: "No shifts in this period." No totals.

*Desktop Shift screen* (summary for reference; detail in 1.3.1): shows, for the open shift, the expected rum as
"Opening + opened - dosed - losses - closed back", the actual rum in the opened jerricans, and the variance. Its
"Before you can close the shift" checks cover trucks and jerricans.

**Validation rules & error messages**

- Web: no validation on the Shifts filters.
- Desktop: none on the Shift screen for this report.

**Data / sync impact**

- Desktop: local `shifts`. Opening rum is taken from the opened jerricans when the shift starts. Closing rum and variance
  are recorded when it closes.
- Web: central `v_shifts`, from the SHIFT records each site synced. Shift status and figures reach the web on sync.
- The Location column shows the location name kept on the shift at the time it started.

**Review notes**

1. **No shift summary exists.** A supervisor (or a reviewer) cannot get, in either app, one page per shift with its trucks,
   litres, rum dosed, incidents and variance. Decide whether that is in scope.
2. **Open shifts outside the default window are hidden.** The web Shifts page defaults to 30 days. A shift opened 40 days ago
   and still open does not appear unless the period is widened. The Dashboard "Open shifts" tile does count it.
3. **Variance display threshold.** Anything under 0.05 g shows as 0. This is a display rule in the web page; confirm it is
   the same threshold the desktop uses to close a shift, or document the difference.
4. **Markings cannot be filtered by shift.** The Markings page filters by location but not by shift, so a reviewer cannot
   list one shift's trucks from the web.

**Code references**

- Web: `web/src/app/pages/shifts.component.ts` (filters, variance badge).
- Desktop: `src/main/java/org/jai/fuelmarking/ui/ShiftTab.java` (reconciliation labels and the expected-rum hint),
  `src/main/java/org/jai/fuelmarking/service/ShiftService.java` (`start`, location rule), `src/main/java/org/jai/fuelmarking/dao/ShiftDao.java`.
- Central: `backend/src/main/java/org/jai/fuelmarking/central/web/ReadService.java` (`shifts`, `overview` open-shift count),
  migration `backend/src/main/resources/db/migration/V17__Locations.sql` (`v_shifts` with location).
- Tests: `backend/src/test/java/org/jai/fuelmarking/central/ReadApiIT.java` (`inventoryIncidentsShiftsAndTransfers`,
  `headOfficeSeesWhereAShiftAndItsMarkingsHappened`), `src/test/java/org/jai/fuelmarking/ReconcileIT.java`.
