# 6. Incident management

An **incident** records a loss of rum: spillage, leakage, or other. Each incident has a type, a quantity in grams, the rum type lost, an optional container, the shift it belongs to (if any), a description, the reporter, and the time it was recorded.

Incidents come from two places:

- **Automatically**, as a side effect of reconciling jerricans or declaring a jerrican empty at a marking site (6.1).
- **Manually**, from the Incidents screen on the desktop (6.2).

Incidents are stored in the desktop `incidents` table and sent to head office as `INCIDENT` events. The web app's Incidents page lists them; it cannot create or change them.

## At a glance

| Feature | Status | Where | Role |
|---|---|---|---|
| 6.1 Incident raised during reconciliation | Partial: raised by marking-site reconciliation and declare-empty; not by the warehouse count; not for a broken seal | Desktop, Reconcile; Declare empty | Supervisor |
| 6.2 Manual incident creation | Implemented (desktop only) | Desktop, Incidents | Supervisor, dosing operator, dispense operator, warehouse manager |

---

## 6.1 Incident raised during reconciliation

- **Status**: Partial. Incidents are raised by the marking-site reconciliation (2.1.10) and by declaring a jerrican empty with rum left (2.1.8). The warehouse stock count (2.2.4, 4.1) raises none. A sealed jerrican with a broken seal raises none either.
- **Where / who**:
  - Desktop, **Reconcile**, cause column and sealed-jerrican check. Supervisor, with an open shift.
  - Desktop, **Declare empty**. Supervisor, with an open shift.
  - Warehouse stock count: no incidents.
- **Preconditions**:
  - An open shift at the site.
  - For a shortfall: the jerrican is `OPENED`, its counted volume is at least 1 g below the system's, and a cause is chosen.
  - For a missing sealed jerrican: the jerrican is `SEALED` and marked **FOUND**, **MISSING** or **SEAL_BROKEN** with a note where required.
  - A signature for the reconciliation, or for the declare-empty step.
- **Behaviour**:
  - **Path A: a short opened jerrican.** The supervisor counts it. If it is short by 1 g or more, the difference shows in red and "Cause (if short)" must be chosen. Choose OTHER and a note is required. When the count is signed, one incident is raised per short jerrican:
    - type: the cause chosen (SPILLAGE, LEAKAGE or OTHER);
    - quantity: the shortfall in grams;
    - container and rum type: the jerrican's own;
    - shift: the open shift;
    - reporter: the supervisor;
    - description: "Found at stock reconciliation", plus ": <note>" if a note was given.
    - The jerrican's volume is set to the counted figure, so the system matches what was found.
  - **Path B: a sealed jerrican not found (MISSING).** The supervisor marks it MISSING and gives a note. An `OTHER` incident is raised for the whole system volume, with the description "Sealed jerrican not found at the stock check: <note>". The jerrican becomes `MISSING` with volume 0. If its volume was already 0, no incident is raised.
  - **Path C: declare empty with rum left.** Covered in [2.1.8](02-container-management.md#218-empty-jerrican-at-marking-site). An incident of the cause chosen is raised for the rum left, with the description "Jerrican declared empty with rum left" (plus the note if any).
  - **Path D: a broken seal (SEAL_BROKEN).** No incident. The count line records the result and the note, and the screen says "flagged for head office".
  - **Surplus** (counted 1 g or more above the system). No incident. A note is required. The system volume is not changed.
  - **Under 1 g.** Neither an incident nor a correction. The system keeps the excess.
- **Validation rules & error messages**:
  - `<barcode> is short - say why: spillage, leakage or other`
  - `<barcode>: describe what happened (cause is other)`
  - `<barcode> holds more than the system says - add a note`
  - `<barcode>: say what you found (where it might be)` (MISSING)
  - `<barcode>: say what you found (what is wrong with the seal)` (SEAL_BROKEN)
  - `The note for <barcode> is too long (300 characters at most)`
  - `<kg> kg of rum is still shown in <barcode> - say what happened to it: spillage, leakage or other` (declare empty)
  - `Describe what happened to the rum that was left (cause is other)` (declare empty)
  - The whole reconciliation is checked before anything is written, so a missing explanation leaves nothing half done.
- **Data / state changes**:
  - `incidents`: one row per short or missing jerrican (and one for declare-empty). Fields: site, shift, container, type, quantity in grams, description, reporter, time recorded (the time of the signing, not the time of the loss), rum type.
  - `containers`: a shortfall sets the volume to the counted figure. A counted figure of zero makes the jerrican `EMPTY` and clears its seals. A MISSING jerrican becomes `MISSING` with volume 0.
  - `stock_count_lines`: the cause, note and `incident_id` for each shortfall, and the `check_result` for each sealed jerrican. Linked to the `stock_counts` row of kind `SHIFT`.
  - Outbox: `INCIDENT` per incident; `STOCK_COUNT` with its lines (each carrying its `incidentId`); `CONTAINER` for each changed jerrican.
- **Sync & reporting impact**:
  - Head office checks that the incident belongs to the sending site, and the shape of its fields (ids, type as text, numeric quantity, a valid time). It does not check the type against the three allowed values.
  - The web Incidents page lists each incident with site, type, quantity in grams, rum type, container, reporter and description.
  - The web Stock counts page shows each line's cause and check label, but does not link a line to its incident.
  - The shift's expected figure takes off every incident that names a container and belongs to the shift (`ShiftService.reconcile`). So a recorded loss explains the shift's variance. An incident without a container does not, see 6.2.
- **Review notes**:
  1. **MISSING clears seal numbers.** Path B runs the incident routine first. That routine takes the volume to zero, and in doing so clears the jerrican's seal numbers and records them as discarded. A jerrican found later has no seal numbers on its record. Confirm this is intended.
  2. **A shortfall to zero skips declare-empty.** The jerrican becomes `EMPTY` with its seals cleared, and the reconciliation signature is the only sign-off. Compare 2.1.8, which asks for a separate signature.
  3. **Missing rum type blocks the whole count.** The incident needs a rum type. For a container that has none on record (the `Records` comment says older records may not have one), a shortfall or declare-empty fails with `Say which rum was lost - choose the rum type (container <barcode> has none on record)`. The reconciliation screen has no rum-type choice, so the count cannot be signed until the record is fixed.
  4. **Surplus is not written back.** The count keeps the lower system figure, so the next count shows the same surplus again. The confirmation says "stock corrected" only for shortfalls.
  5. **Broken seal is not an incident.** The result is recorded on the count line and flagged for head office only through the count. If a seal has been broken, the rum may be at risk; decide whether that should raise an incident.
  6. **Incident time is the signing time.** `IncidentDao.insert` records the time it is called. The time of the loss is never recorded.
  7. **Generic description.** A shortfall's description is "Found at stock reconciliation" with the note, if any. The cause and the rum type are in their own fields, so the description alone does not say which jerrican or why.
  8. **The shift can balance by recording losses.** Every container-linked incident reduces the shift's expected rum. A shift that shows a large loss balances on paper. Reviewers should check the ratio of incidents to dispensed rum, not only the variance.
  9. **Warehouse shortages have no incident.** See 2.2.4 and 4.1.
- **Code references**:
  - `src/main/java/org/jai/fuelmarking/service/ReconcileService.java`: `submit`, `TOLERANCE_G`.
  - `src/main/java/org/jai/fuelmarking/service/ContainerService.java`: `declareEmpty`.
  - `src/main/java/org/jai/fuelmarking/service/IncidentService.java`: `reportIn`.
  - `src/main/java/org/jai/fuelmarking/dao/IncidentDao.java`: `insert`.
  - `src/main/java/org/jai/fuelmarking/dao/StockCountDao.java`: `insert`, `publish`.
  - `src/main/java/org/jai/fuelmarking/dao/ContainerDao.java`: `setVolume`, `markMissing`, `discardJerricanSeals`.
  - `src/main/java/org/jai/fuelmarking/service/ShiftService.java`: `reconcile` (the incidents term).
  - `src/main/java/org/jai/fuelmarking/ui/ReconcilePanel.java`: `summary`, `entries`, `sealedChecks`.
  - `src/main/java/org/jai/fuelmarking/ui/ReconcileTab.java`: `sign`.
  - `src/main/java/org/jai/fuelmarking/ui/OpenJerricanTab.java`: `declareEmpty`.
  - `backend/src/main/java/org/jai/fuelmarking/central/sync/PayloadChecks.java`: `INCIDENT` and `STOCK_COUNT` shape checks.
  - `web/src/app/pages/incidents.component.ts`, `web/src/app/pages/stock-counts.component.ts`.
  - Tests: `src/test/java/org/jai/fuelmarking/ReconcileIT.java`, `SealedCheckIT.java`, `ReturnEmptyIT.java`, `IncidentRumIT.java`.

## 6.2 Manual incident creation

- **Status**: Implemented on the desktop. The web app's Incidents page is read-only.
- **Where / who**: Desktop, **Incidents** tab (`IncidentTab`). Shown to the supervisor, the dosing operator, the dispense operator, and the warehouse manager. The web app has no way to create an incident.
- **Preconditions**:
  - Signed in with one of the four roles above.
  - A supervisor needs an open shift ("Start a shift first"). Other roles record the incident with whatever shift the session holds, which is none for the warehouse manager.
  - A rum type is chosen, unless the container barcode names a container that has one.
- **Behaviour**:
  1. Choose the type: SPILLAGE, LEAKAGE or OTHER. SPILLAGE is selected by default.
  2. Enter the quantity lost. The field is labelled "Quantity lost (g)" and takes grams.
  3. Optionally type a container barcode. When the field loses focus or Enter is pressed, the rum type is taken from that container and the rum field is locked. The field is labelled "Container barcode (optional - reduces its volume)".
  4. If no container is named, choose the rum type from the list ("Which rum was lost?").
  5. Describe what happened. It is required for OTHER.
  6. Press **Report incident**. The form clears and the table of the last seven days at this site refreshes.
  - If a container was named, its volume is reduced by the quantity, to zero at lowest.
- **Validation rules & error messages**:
  - Screen: `Quantity must be a number`.
  - Service, in this order: `Start a shift first` (supervisor with no shift); `Type must be SPILLAGE, LEAKAGE or OTHER`; `Quantity must be positive`; `Say what happened - an incident of type OTHER needs a description`; `Unknown container <barcode>`; `Container <barcode> holds rum type <A>, not <B>`; `Say which rum was lost - choose the rum type` (with " (container <barcode> has none on record)" if a container was named); `Unknown rum type <code>`.
  - Role check: `Action not permitted for role <role>`.
- **Data / state changes**:
  - `incidents`: site, shift (if any), container (if named), type, quantity in grams, description, reporter, time recorded (now), rum type. The rum type is the container's if it has one, otherwise the one chosen.
  - If a container was named: its `current_volume_ml` is reduced by the quantity, to zero at lowest. Reaching zero also makes it `EMPTY` and clears its seals, whatever its status was (see Review notes).
  - Outbox: `INCIDENT`, and `CONTAINER` when a container was changed.
- **Sync & reporting impact**:
  - The incident goes to head office. Its site must be the sending site.
  - The web Incidents page lists it, with filters for site, type, rum type and date. The default date range is the last 89 days. Columns: when, site, type, quantity in grams, rum type, container, reporter, description.
  - An incident without a container is listed on the web, but it does not change the shift's expected rum (`ShiftService.reconcile` counts only incidents that name a container). It therefore leaves the shift variance unexplained.
  - The desktop table shows only the last seven days at this site.
- **Review notes**:
  1. **Grams on this screen only.** Every other screen works in kilograms. Typing "5" here records 5 g. Confirm the unit, or change the screen to kilograms.
  2. **Quantity not capped.** If the quantity is more than the container holds, the container goes to zero but the incident keeps the full quantity typed. `IncidentService.reportIn` stores the requested quantity, not the amount actually taken from the container.
  3. **No status check.** An incident that names a `SEALED` or `IN_TRANSIT` container reduces it. If that takes all its rum, the container becomes `EMPTY` and its seals are cleared. That bypasses declare-empty (2.1.8), its signature, and the shift's check that no jerrican is left open. It can also make a jerrican `EMPTY` while it is still in transit. Decide whether incidents should be limited to `OPENED` jerricans and bulk containers at the site.
  4. **No site check on the container.** `IncidentService.reportIn` looks the container up by barcode only. A supervisor at one site can reduce a container held at another. This looks like a defect.
  5. **Manual incidents without a container do not affect the shift.** See 6.1 for the effect on the shift variance. A loss recorded without a container is visible to head office but is not explained in the shift's figures.
  6. **Rum type locked from the container.** A container's rum type cannot be overridden: naming a different one is refused. A container with no rum type on record needs the rum chosen.
  7. **Time is the time of entry.** There is no field for the time the loss happened.
  8. **Shift link depends on the role.** Only a supervisor is required to have an open shift. A warehouse manager has no shift, so its incidents are not linked to one and do not affect any shift figure. For dosing and dispense operators, the incident takes whatever shift the session holds; this was not traced in every path.
- **Code references**:
  - `src/main/java/org/jai/fuelmarking/ui/IncidentTab.java`: `report`, `refresh`, the container and rum handling.
  - `src/main/java/org/jai/fuelmarking/service/IncidentService.java`: `report`, `reportIn`, `list`, `TYPES`.
  - `src/main/java/org/jai/fuelmarking/dao/IncidentDao.java`: `insert`, `listBySite`.
  - `src/main/java/org/jai/fuelmarking/dao/ContainerDao.java`: `setVolume`, `discardJerricanSeals`.
  - `src/main/java/org/jai/fuelmarking/service/ShiftService.java`: `reconcile` (incidents term).
  - `src/main/java/org/jai/fuelmarking/ui/MainView.java`: which roles have the Incidents tab.
  - `web/src/app/pages/incidents.component.ts`.
  - `backend/src/main/java/org/jai/fuelmarking/central/sync/PayloadChecks.java`: `INCIDENT` shape check.
  - Tests: `src/test/java/org/jai/fuelmarking/IncidentRumIT.java`.
