package org.jai.fuelmarking.model;

import java.time.LocalDateTime;
import java.util.UUID;

public final class Records {
    private Records() {}

    public record Site(UUID id, String code, String name, String siteType) {
        @Override public String toString() { return name; }
    }

    public record Omc(UUID id, String code, String name) {
        @Override public String toString() { return name; }
    }

    public record AppUser(UUID id, String username, String fullName, Role role, UUID siteId) {
        @Override public String toString() { return fullName; }
    }

    public record Shift(UUID id, UUID siteId, UUID supervisorId, LocalDateTime startedAt, LocalDateTime endedAt,
                        String status, double openingRumMl, Double closingRumMl, Double varianceMl) {}

    /**
     * A container type from the master data (maintained in the web app): bulk (IBC, drum ...) or jerrican (plastic, metal ...),
     * the number of seals it carries and, optionally, its usual capacity in grams of rum. Serialised as its code (what containers store).
     */
    public record ContainerType(String code, String name, String category, int seals, Double defaultCapacityG) {
        public boolean isBulk() { return "BULK".equals(category); }
        @com.fasterxml.jackson.annotation.JsonValue public String code() { return code; }
        @Override public String toString() { return name; }
    }

    public record Container(UUID id, String barcode, ContainerType type, double capacityMl, double currentVolumeMl,
                            String topSeal, String bottomSeal, String status, UUID siteId, UUID sourceContainerId,
                            String rumTypeCode) {}

    /** A fuel type or rum type from the master data (maintained in the web app). */
    /** rumTypeCode: the one rum type this fuel is marked with (set at head office). */
    public record FuelType(String code, String name, String rumTypeCode) {
        public FuelType(String code, String name) { this(code, name, null); }
        @Override public String toString() { return name; }
    }

    /** One dose of a truck: the rum for one compartment, as its litres call for it and as it was weighed ("ALL" for a truck recorded without compartments). */
    public record MarkingDose(String compartment, String rumType, double requiredG, double measuredG, String source, UUID containerId, LocalDateTime dosedAt) {}

    /** What one compartment needs: its fuel, the rum type that fuel is marked with, its litres and so the rum to dose, in grams. */
    public record RequiredDose(String compartment, String fuelType, String rumType, double litres, double requiredG) {}
    public record RumType(String code, String name, String color) { @Override public String toString() { return name; } }

    /** A jerrican registered at head office; capacityG is grams of rum. Its container type says how many seals it carries. */
    public record RegisteredJerrican(UUID id, String barcode, ContainerType type, double capacityG, String rumTypeCode) {
        public int seals() { return type.seals(); }
    }

    /**
     * A signed count of rum. kind WAREHOUSE: the IBCs and drums at a warehouse (shiftId is null); kind SHIFT: the opened jerricans a
     * supervisor reconciles during a shift. Quantities are grams; the variance is counted minus what the system held.
     */
    public record StockCount(UUID id, UUID siteId, UUID countedBy, LocalDateTime countedAt, String comment,
                             double totalSystemG, double totalCountedG, String kind, UUID shiftId) {
        public StockCount(UUID id, UUID siteId, UUID countedBy, LocalDateTime countedAt, String comment, double totalSystemG, double totalCountedG) {
            this(id, siteId, countedBy, countedAt, comment, totalSystemG, totalCountedG, "WAREHOUSE", null);
        }
        public double totalVarianceG() { return totalCountedG - totalSystemG; }
    }

    /** cause, note and incidentId are set on a shift reconciliation line that came up short (or over): why, and the incident raised. */
    public record StockCountLine(String barcode, String containerType, String rumTypeCode, double systemG, double countedG, String source,
                                 String cause, String note, UUID incidentId, String checkResult) {
        public StockCountLine(String barcode, String containerType, String rumTypeCode, double systemG, double countedG, String source,
                              String cause, String note, UUID incidentId) {
            this(barcode, containerType, rumTypeCode, systemG, countedG, source, cause, note, incidentId, null);
        }
        public StockCountLine(String barcode, String containerType, String rumTypeCode, double systemG, double countedG, String source) {
            this(barcode, containerType, rumTypeCode, systemG, countedG, source, null, null, null, null);
        }
        public double varianceG() { return countedG - systemG; }
    }

    /**
     * The check of one SEALED jerrican during a reconciliation: result FOUND (it is there and its seal is intact), MISSING (it is not there), or
     * SEAL_BROKEN (it is there, but its seal is broken or is not the one on record). A note is needed unless it was found intact.
     */
    public record SealedCheck(String barcode, String result, String note) {}

    /** What was entered for one container; source is SCALE if read from the scale, MANUAL if typed. cause (SPILLAGE, LEAKAGE, OTHER) and note explain a difference. */
    public record StockEntry(String barcode, double countedG, String source, String cause, String note) {
        public StockEntry(String barcode, double countedG, String source) { this(barcode, countedG, source, null, null); }
    }

    /** rumTypeCode: which rum was lost (that of the container named, or the one chosen); null only on records from before it was tracked. */
    public record Incident(UUID id, UUID siteId, UUID shiftId, UUID containerId, String type, double quantityMl,
                           String description, UUID reportedBy, LocalDateTime occurredAt, String rumTypeCode) {}

    public record Reconciliation(double openingMl, double openedDuringShiftMl, double dispensedMl, double incidentMl, double closedBackMl,
                                 double expectedClosingMl, double actualClosingMl, double varianceMl) {}

    /** kind SUPPLY: sealed containers out of a warehouse; RETURN: finished (empty) jerricans back to a warehouse. */
    public record Transfer(UUID id, UUID fromSiteId, UUID toSiteId, String status, LocalDateTime dispatchedAt, String kind) {
        public Transfer(UUID id, UUID fromSiteId, UUID toSiteId, String status, LocalDateTime dispatchedAt) {
            this(id, fromSiteId, toSiteId, status, dispatchedAt, "SUPPLY");
        }
    }

    public record TransferItem(String barcode, boolean received) {}

    /** Generic tabular report result. */
    public record ReportTable(java.util.List<String> headers, java.util.List<java.util.List<String>> rows) {}
}
