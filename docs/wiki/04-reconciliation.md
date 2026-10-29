# 4. Reconciliation

This page covers reconciliation **at the warehouse**: the warehouse manager's count of the rum held in IBCs and drums (4.1), and the question of whether jerricans are reconciled there (4.2).

The system has three different checks that are all called "reconciliation" or "count". Keep them apart when reading the wiki:

| Check | Who | Where it is in the code | Changes stock? | Raises incidents? |
|---|---|---|---|---|
| Warehouse stock count (4.1, 2.2.4) | Warehouse manager | `StockCountService` (kind `WAREHOUSE`) | No. It is a record only. | No |
| Jerricans and sealed-jerrican check at a marking site (2.1.10) | Supervisor, during a shift | `ReconcileService` (kind `SHIFT`) | Yes: shortfalls reduce the jerrican's volume, and a missing sealed jerrican becomes `MISSING`. | Yes (see 6.1) |
| Shift-close figures | Supervisor, at shift close | `ShiftService.reconcile` | Not applicable. It compares the shift's expected and actual rum and does not write stock. | Not applicable |

Only the first is a warehouse reconciliation. The others are described in [02-container-management.md](02-container-management.md).

## At a glance

| Feature | Status | Where | Role |
|---|---|---|---|
| 4.1 IBC reconciliation at the warehouse | Implemented (record only) | Desktop, Stock count; web, Stock counts | Warehouse manager |
| 4.2 Jerrican reconciliation at the warehouse | Not found | none (closest: 2.1.10 at a marking site) | none |

---

## 4.1 IBC reconciliation at the warehouse

- **Status**: Implemented, as a signed record. The count is never written back to the stock.
- **Where / who**: Desktop, **Stock count** tab (`StockCountTab`). Warehouse manager only. The web app's **Stock counts** page lists the result as "Warehouse count". It is read-only.
- **Preconditions**:
  - At least one bulk container (IBC or drum) is at this warehouse and is not `IN_TRANSIT`.
  - A signature is captured.
  - The screen steps and the on-screen controls are described in [2.2.4](02-container-management.md#224-reconcile-ibc-at-warehouse). This section covers the rules.
- **Behaviour (rules)**:
  1. **Scope.** Every bulk container whose `site_id` is this warehouse and whose status is not `IN_TRANSIT`. Bulk containers on the road belong to the receiving site's count, not this one.
  2. **All or nothing.** Every one must be counted in the same submission. A partial count is refused, and nothing is recorded.
  3. **Counted value.** Entered in kilograms, typed or read from the scale, and stored in grams rounded to 0.1 g.
  4. **Difference.** Counted minus system. There is no tolerance: any difference is recorded.
  5. **Effect.** A `stock_counts` row (kind `WAREHOUSE`, no shift) and one `stock_count_lines` row per container, with the system and counted grams and the source (`SCALE` or `MANUAL`). No container changes. No incident is raised. No cause or note is asked for.
  6. **Signature.** Required. The count is refused without one.
- **Validation rules & error messages**: As listed in 2.2.4. The rules specific to the count are:
  - `There is no IBC or drum at <site> to count`
  - `Count every IBC and drum - missing: <list>`
  - `<barcode> is not an IBC or drum held at <site>`
  - `The count for <barcode> cannot be negative`
  - `<barcode> was counted twice`
  - `The comment is too long (500 characters at most)`
  - `Signature is required`
- **Data / state changes**:
  - `stock_counts`: kind `WAREHOUSE`, `shift_id` empty, system total, counted total, optional trimmed comment.
  - `stock_count_lines`: barcode, container type, rum type, system grams, counted grams, source. The `cause`, `note`, `incident_id` and `check_result` fields stay empty for this kind.
  - `containers`: unchanged.
  - `signatures`: step `STOCK_COUNT`.
  - Outbox: `STOCK_COUNT`, with its lines, the totals and the variance.
- **Sync & reporting impact**:
  - Head office checks the count's shape: totals, variance, a non-empty list of lines, and barcodes that are not control characters. The signature must belong to the same site (`SyncService`, `PayloadChecks`).
  - The web Stock counts page lists it with kind "Warehouse count", its system, counted and difference totals, and the number of containers. Opening a count shows each line's system value, counted value, difference, source ("weighed" or "typed") and rum type.
  - The web flags a line as different when its difference is 1 g or more.
- **Review notes**:
  - **No explanation for a shortage.** A shortage of an IBC is recorded with a negative difference and nothing else. No cause, no note, no incident, and no change to stock. Compare 6.1, where the marking site asks for a cause and raises an incident. Should a warehouse shortage raise an incident as well?
  - **Difference is never corrected.** The count does not change stock. So if the IBC really is short, the next count shows the same difference again. The only route to a corrected figure is a manual incident (6.2), and nothing links it to the count. Decide the policy.
  - **In-transit containers are excluded.** A bulk container that has left the warehouse is not in this count at all. A loss in transit shows up only at the receiving site, if it shows up at all.
  - **Empty containers are counted at zero.** A drawn-down IBC still appears in every count, at zero (2.2.3).
  - **Only bulk containers.** Jerricans are not counted here (4.2).
  - **Threshold mismatch.** The desktop colours any difference that is not zero in kilograms. The web flags 1 g or more. This is cosmetic but can confuse a reviewer.
  - **Comment trimmed.** A blank comment is stored as no comment. A comment over 500 characters is refused.
- **Code references**:
  - `src/main/java/org/jai/fuelmarking/ui/StockCountTab.java`: `Row`, `CountCell`, `submit`, `refresh`, `signed`.
  - `src/main/java/org/jai/fuelmarking/service/StockCountService.java`: `toCount`, `submit`, `history`, `lines`.
  - `src/main/java/org/jai/fuelmarking/dao/StockCountDao.java`: `insert`, `history`, `publish`.
  - `src/main/java/org/jai/fuelmarking/model/Records.java`: `StockCount`, `StockCountLine`.
  - `src/main/resources/db/migration/V16__Stock_counts.sql`, `V18__Shift_reconciliation.sql`: table definitions. `kind` was added in V18 with default `WAREHOUSE`.
  - `backend/src/main/java/org/jai/fuelmarking/central/sync/PayloadChecks.java`: `STOCK_COUNT` shape check.
  - `web/src/app/pages/stock-counts.component.ts`.
  - Tests: `src/test/java/org/jai/fuelmarking/StockCountIT.java`.

## 4.2 Jerrican reconciliation at the warehouse

- **Status**: Not found.
- **Where / who**: None. No warehouse screen counts or checks jerricans.
- **What exists instead**:
  - **The stock count covers bulk containers only.** `StockCountService.toCount` and `StockCountService.submit` keep containers where `type.isBulk()` is true. Jerricans at the warehouse are not counted. That covers both `EMPTY` jerricans, waiting to be refilled, and `SEALED` jerricans, refilled and waiting to be sent.
  - **Inventory shows them, but reconciles nothing.** The desktop Inventory tab and the web Inventory page list every container at the site, including jerricans, with their status and seals. Nothing compares that list with a physical count.
  - **Receiving returns confirms arrival, not stock.** The **Receive returns** screen (2.1.9) ticks each empty jerrican that arrived. That records the transfer's receipt, item by item. It is not a count against the system.
  - **Marking-site reconciliation is the nearest equivalent.** The supervisor's check of opened and sealed jerricans is [2.1.10](02-container-management.md#2110-reconcile-jerricans-at-marking-site). It is not available to the warehouse, and the warehouse has no open shift.
- **Preconditions / behaviour / validation / data / sync**: Not applicable.
- **Review notes**:
  - **A missing jerrican at the warehouse would not be detected.** A `SEALED` jerrican that left the warehouse by an unrecorded route, or a jerrican lost from the store, would not be found by any count. An `EMPTY` jerrican holds no rum, so the rum risk is small; a `SEALED` one holds rum.
  - **Options to decide**: extend the warehouse stock count to jerricans, with the same seal check as 2.1.10 for `SEALED` ones; or accept the receipt and inventory records as the control.
- **Cross-references**: [2.1.10](02-container-management.md#2110-reconcile-jerricans-at-marking-site) (marking-site jerrican reconciliation), [2.2.4](02-container-management.md#224-reconcile-ibc-at-warehouse) (warehouse IBC count), [6.1](06-incident-management.md#61-incident-raised-during-reconciliation) (incidents from reconciliation).
- **Code references**:
  - `src/main/java/org/jai/fuelmarking/service/StockCountService.java`: `toCount`, `submit` (bulk filter).
  - `src/main/java/org/jai/fuelmarking/ui/InventoryTab.java`: `refresh` (lists all containers).
  - `src/main/java/org/jai/fuelmarking/ui/WarehouseTab.java`, `TransferTab.java`: receipt of returns.
