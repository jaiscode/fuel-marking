package org.jai.fuelmarking.central.web;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Response types of the read API used by the web app. */
public final class ReadModels {
    private ReadModels() {}

    public record Page<T>(List<T> items, long total, int page, int size) {}

    public record DayPoint(LocalDate day, long trucks, double rumMl) {}

    public record Overview(long trucks, long completed, double fuelLitres, double rumMl, long incidents, double incidentMl,
                           long openShifts, long sitesReporting, List<DayPoint> byDay) {}

    public record SiteStatus(UUID id, String code, String name, String siteType, boolean active,
                             LocalDateTime lastSyncAt, Long lastSyncAgeSeconds, boolean shiftOpen,
                             long trucksToday) {}

    public record MarkingRow(UUID id, UUID siteId, String siteName, String orderNumber, String truckNumber, String trailerNumber, String omc,
                             String fuelType, double declaredVolumeL, double rumMl, String status, String rumType,
                             LocalDateTime registeredAt, LocalDateTime completedAt) {}

    /** fuelType / rumType are empty on markings from before a compartment carried its own fuel. */
    public record Compartment(String number, double capacity, double volume, String fuelType, String rumType) {}

    /** One dose of a marking: the rum for one compartment (empty on older records), as required and as weighed. */
    public record Dose(String compartment, String rumType, double requiredG, double measuredG, String source, LocalDateTime dosedAt) {}

    public record SignatureInfo(UUID id, String step, String signedBy, LocalDateTime signedAt) {}

    /** doseRequiredG is what the rules called for, doseMeasuredG what was weighed; doseSource is SCALE or MANUAL. */
    public record MarkingDetail(MarkingRow marking, LocalDateTime dosedAt, LocalDateTime dispensedAt,
                                List<Compartment> compartments, List<SignatureInfo> signatures,
                                Double doseRequiredG, Double doseMeasuredG, String doseSource, List<Dose> doses) {}

    public record SummaryRow(String key, String label, long trucks, double fuelLitres, double rumMl) {}

    public record ContainerRow(UUID id, UUID siteId, String siteName, String barcode, String type, String status,
                               double capacityMl, double volumeMl, String topSeal, String bottomSeal) {}

    public record InventoryRow(String siteName, String type, String status, long containers, double volumeMl) {}

    public record IncidentRow(UUID id, UUID siteId, String siteName, String type, double quantityMl, String description,
                              String container, String reportedBy, LocalDateTime occurredAt, String rumType) {}

    public record ShiftRow(UUID id, UUID siteId, String siteName, String supervisor, LocalDateTime startedAt,
                           LocalDateTime endedAt, String status, double openingMl, Double closingMl, Double varianceMl) {}

    public record TransferRow(UUID id, String fromSite, String toSite, String status, LocalDateTime dispatchedAt,
                              long items, long received, String kind) {}

    /** received: the destination site has taken this container in. */
    public record TransferItem(String barcode, boolean received) {}

    /** A stock count (kind WAREHOUSE: IBCs and drums) or a shift reconciliation (kind SHIFT: opened jerricans, with the cause of each shortfall), as counted against what the system held. */
    public record StockCountRow(UUID id, UUID siteId, String siteName, String countedBy, LocalDateTime countedAt, String comment,
                                double totalSystemG, double totalCountedG, double totalVarianceG, long containers, long differing, String kind) {}

    public record StockCountLine(String barcode, String containerType, String rumTypeCode, double systemG, double countedG, double varianceG, String source, String cause, String note,
                                String check) {}

    public record StockCountDetail(StockCountRow count, List<StockCountLine> lines) {}

    public record TransferDetail(TransferRow transfer, List<TransferItem> items) {}

    /** Names for filter drop-downs; readable by anyone who may read reports. */
    public record Lookups(List<Lookup> sites, List<Lookup> omcs, List<Lookup> fuelTypes, List<Lookup> rumTypes, List<Lookup> containerTypes) {}

    /** color is only set for rum types. */
    public record Lookup(UUID id, String code, String name, boolean active, String color) {}
}
