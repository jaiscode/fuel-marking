# 8. Other modules

Behaviour wiki, module 8. This page holds features that do not fit the other modules. It currently has one item.

## At a glance

| Ref | Feature | Status | Where | Who |
|---|---|---|---|---|
| 8.1 | QC sample | **Not found in code** | None. Closest: sealed-jerrican check, dose weighing tolerance, marking verification | - |

---

## 8.1 QC sample

**Status**: **Not found in code.** No quality-control sample is taken, recorded, tested or reported anywhere in the system. There is
no entity, table, API route, screen, report or setting for it.

**What was searched.** The whole repository (desktop, backend, web, migrations, configuration, scripts and docs) was searched,
case-insensitively, for: "QC", "quality", "qualit", "sample" / "sampling", "lab", "laboratory" and "assay". The only hits were:

- Development seed data and its documentation: `src/main/resources/db/migration/V4__Insert_sample_data.sql`,
  `src/main/java/org/jai/fuelmarking/config/DevSeed.java`, the "sample data" notes in `README.md` and `scripts/dev-up.sh`. These are
  sample records for development, not a sample taken for quality control.
- Package-lock entries and unrelated text, which are not relevant.

**Where / who**: not applicable.

**Preconditions**: not applicable.

**Behaviour**: none. The system does not record a sample, a test result, a pass or fail, or a laboratory reference.

**Validation rules & error messages**: none, because there is no feature.

**Data / sync impact**: none.

**Closest existing behaviour** (none of these is a quality-control sample; listed so a reviewer can see what is already covered):

1. **Sealed-jerrican check at stock reconciliation.** When the warehouse or a supervisor reconciles stock, each sealed jerrican
   is looked at and given one result: FOUND (seal intact), MISSING, or SEAL_BROKEN. Nothing is weighed or tested, because
   weighing would break the seal. This is a physical presence and seal check, recorded on the stock count line
   (`check_result`, migration `V26__Sealed_jerrican_check.sql`). See [04-reconciliation.md](04-reconciliation.md).
2. **Dose weighing tolerance.** Each rum dose is weighed on the scale and must fall within a tolerance of the required dose
   (`dose-tolerance-percent`, 2.0 per the desktop settings). This controls how accurately rum is dosed. It is a process
   control, not a sample test. It is described in the marking module, see [03-marking.md](03-marking.md) (dosing).
3. **Marking verification.** The final step of a marking is verification, which the verifying person signs for (the sign
   button reads "Verified - sign"). It confirms the marking was completed. It does not test the fuel. See
   [03-marking.md](03-marking.md) (verify).
4. **Incidents.** Losses or spills are recorded as incidents with a type of SPILLAGE, LEAKAGE or OTHER. An incident is a record of
   an event, not a sample. See [06-incident-management.md](06-incident-management.md).

**Review notes**

1. **Confirm the requirement.** If a QC sample is needed (for example, a sample taken from a truck or a jerrican, sent for a lab
   test, with a result and a pass or fail), it is new work. It would need: a sample record linked to the marking or the
   container; the sample's identifier and when it was taken; who took it; a result and its date; whether a failed result blocks
   dispatch or verification; reporting, and synchronisation to head office.
2. **Existing gates should not be mistaken for it.** The seal check, the dose tolerance and the verification signature could be
   read as quality controls. A reviewer should confirm they are the controls intended, and that a fuel-quality control is
   either out of scope or planned as separate work.
3. **No planned design is in the repository.** No requirement, story or ADR in the repository mentions QC samples. A search of
   the `docs/` folder found none.

**Code references**: none for the feature. Closest existing code, for context only:

- `backend/src/main/resources/db/migration/V26__Sealed_jerrican_check.sql` (the check result column). The desktop reconcile screen
  is `src/main/java/org/jai/fuelmarking/ui/ReconcilePanel.java`.
- `src/main/java/org/jai/fuelmarking/service/MarkingService.java` (dose tolerance and the verify step, `verify`).
- `src/main/java/org/jai/fuelmarking/service/IncidentService.java` (incident types `SPILLAGE`, `LEAKAGE`, `OTHER`).
