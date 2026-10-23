package org.jai.fuelmarking.central.sync;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Shape checks for pushed documents. The typed SQL views cast these fields, so a malformed value accepted here
 * would make every report fail; reject it at the door instead.
 */
final class PayloadChecks {
    private static final int MAX_SIGNATURE_CHARS = 1_500_000;

    private PayloadChecks() {}

    /** Returns a reason if the payload is unusable, otherwise null. */
    static String check(String type, String entityId, JsonNode p) {
        try {
            switch (type) {
                case "TRUCK_MARKING" -> {
                    sameId(entityId, uuid(p, "uuid", true));
                    uuid(p, "siteId", true); uuid(p, "shiftId", false); uuid(p, "omcId", false);
                    text(p, "orderNumber"); text(p, "truckNumber"); text(p, "fuelType"); text(p, "status");
                    number(p, "declaredVolume", true); number(p, "doseCalculatedMl", false); number(p, "doseMeasuredG", false);
                    doseSource(p);
                    time(p, "registeredTime", true); time(p, "dosedTime", false); time(p, "dispensedTime", false); time(p, "completedTime", false);
                    if (p.has("compartments") && !p.get("compartments").isNull() && !p.get("compartments").isArray()) return "compartments must be an array";
                }
                case "SHIFT" -> {
                    sameId(entityId, uuid(p, "id", true));
                    uuid(p, "siteId", true); uuid(p, "supervisorId", true); text(p, "status");
                    time(p, "startedAt", true); time(p, "endedAt", false);
                    number(p, "openingRumMl", true); number(p, "closingRumMl", false); number(p, "varianceMl", false);
                }
                case "INCIDENT" -> {
                    sameId(entityId, uuid(p, "id", true));
                    uuid(p, "siteId", true); uuid(p, "shiftId", false); uuid(p, "containerId", false); uuid(p, "reportedBy", true);
                    text(p, "type"); number(p, "quantityMl", true); time(p, "occurredAt", true);
                }
                case "CONTAINER" -> {
                    sameId(entityId, uuid(p, "id", true));
                    uuid(p, "siteId", true); text(p, "barcode"); text(p, "type"); text(p, "status");
                    number(p, "capacityMl", true); number(p, "currentVolumeMl", true);
                }
                case "STOCK_COUNT" -> {
                    sameId(entityId, uuid(p, "id", true));
                    uuid(p, "siteId", true); uuid(p, "countedBy", true); time(p, "countedAt", true);
                    number(p, "totalSystemG", true); number(p, "totalCountedG", true); number(p, "totalVarianceG", true);
                    JsonNode lines = p.path("lines");
                    if (!lines.isArray() || lines.isEmpty()) return "lines must be a non-empty array";
                    for (JsonNode l : lines) {
                        if (!l.isObject()) return "line must be an object";
                        text(l, "barcode"); number(l, "systemG", true); number(l, "countedG", true); number(l, "varianceG", true);
                    }
                }
                case "SIGNATURE" -> {
                    sameId(entityId, uuid(p, "id", true));
                    uuid(p, "entityId", true); uuid(p, "signedBy", true); text(p, "entityType"); text(p, "step");
                    String png = text(p, "signaturePng");
                    if (png.length() > MAX_SIGNATURE_CHARS) return "Signature image too large";
                    Base64.getDecoder().decode(png);
                }
                case "TRANSFER" -> {
                    JsonNode t = p.path("transfer");
                    sameId(entityId, uuid(t, "id", true));
                    uuid(t, "fromSiteId", true); uuid(t, "toSiteId", true); text(t, "status"); time(t, "dispatchedAt", true);
                    JsonNode items = p.path("items");
                    if (!items.isArray()) return "items must be an array";
                    for (JsonNode i : items) {
                        if (!i.isObject()) return "item must be an object";
                        text(i, "barcode");
                    }
                }
                default -> { }
            }
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Where the dose figure came from: read off the scale, or typed in. Anything else is not a value this system writes. */
    private static void doseSource(JsonNode p) {
        JsonNode n = p.path("doseSource");
        if (n.isMissingNode() || n.isNull()) return;
        if (!n.isTextual() || !(n.asText().equals("SCALE") || n.asText().equals("MANUAL"))) throw new IllegalArgumentException("Bad doseSource");
    }

    private static void sameId(String entityId, UUID id) {
        if (!id.toString().equals(entityId)) throw new IllegalArgumentException("entityId does not match the document id");
    }

    private static UUID uuid(JsonNode p, String f, boolean required) {
        JsonNode n = p.path(f);
        if (n.isMissingNode() || n.isNull()) {
            if (required) throw new IllegalArgumentException("Missing " + f);
            return null;
        }
        try { return UUID.fromString(n.asText()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Bad " + f); }
    }

    private static String text(JsonNode p, String f) {
        JsonNode n = p.path(f);
        if (!n.isTextual() || n.asText().isBlank()) throw new IllegalArgumentException("Missing " + f);
        return n.asText();
    }

    private static void number(JsonNode p, String f, boolean required) {
        JsonNode n = p.path(f);
        if (n.isMissingNode() || n.isNull()) {
            if (required) throw new IllegalArgumentException("Missing " + f);
            return;
        }
        if (!n.isNumber()) throw new IllegalArgumentException("Bad " + f);
    }

    private static void time(JsonNode p, String f, boolean required) {
        JsonNode n = p.path(f);
        if (n.isMissingNode() || n.isNull()) {
            if (required) throw new IllegalArgumentException("Missing " + f);
            return;
        }
        try { LocalDateTime.parse(n.asText()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Bad " + f); }
    }
}
