package org.jai.fuelmarking.central.sync;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shape checks for pushed documents. The typed SQL views cast these fields, so a malformed value accepted here
 * would make every report fail; reject it at the door instead.
 */
final class PayloadChecks {
    private static final int MAX_SIGNATURE_CHARS = 1_500_000;
    static final int MAX_TRANSFER_ITEMS = 1000;
    /** Exactly what the desktop signs (SignatureDao.save callers): truck markings, shifts, stock counts and transfers. */
    static final java.util.Set<String> SIGNED_TYPES = java.util.Set.of("TRUCK_MARKING", "SHIFT", "STOCK_COUNT", "TRANSFER");
    /** The desktop stores barcodes in VARCHAR(100). */
    static final int MAX_BARCODE_CHARS = 100;
    /**
     * Barcodes are scanned or typed as free text, and a rejected event is never re-sent by the desktop (it is parked as
     * refused), so be generous: any letter, mark, digit, punctuation, symbol or space character (including a pasted
     * no-break space), plus the GS1 group separator U+001D that scanners emit. Still refused: every other control
     * character, formatting characters (bidi overrides, zero-width) and line/paragraph separators. Never normalised:
     * central's copy must match the site's byte for byte.
     */
    private static final Pattern BARCODE = Pattern.compile("[\\p{L}\\p{M}\\p{N}\\p{P}\\p{S}\\p{Zs}\\x{1D}]+");
    /**
     * What a PostgreSQL {@code timestamp} (and the typed report views that cast these fields) can hold. Wider than any
     * plausible date on purpose: a site whose clock reset to 1970 must not lose its records for good. Years 0000 and below,
     * and five-digit years, parse in Java but not in PostgreSQL.
     */
    static final int MIN_YEAR = 1, MAX_YEAR = 9999;

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
                    uuid(p, "siteId", true); barcode(p, "barcode"); text(p, "type"); text(p, "status");
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
                        barcode(l, "barcode"); number(l, "systemG", true); number(l, "countedG", true); number(l, "varianceG", true);
                    }
                }
                case "SIGNATURE" -> {
                    sameId(entityId, uuid(p, "id", true));
                    uuid(p, "entityId", true); uuid(p, "signedBy", true); text(p, "step");
                    if (!SIGNED_TYPES.contains(text(p, "entityType"))) throw new IllegalArgumentException("Bad entityType (not something a site signs)");
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
                    if (items.size() > MAX_TRANSFER_ITEMS) return "At most " + MAX_TRANSFER_ITEMS + " items per transfer";
                    for (JsonNode i : items) {
                        if (!i.isObject()) return "item must be an object";
                        barcode(i, "barcode");
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
        UUID id;
        try { id = UUID.fromString(n.asText()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Bad " + f); }
        // ids are compared as text (site ownership, transfer ends), so only the canonical lower-case form is accepted
        if (!id.toString().equals(n.asText())) throw new IllegalArgumentException("Bad " + f + " (not a canonical lower-case UUID)");
        return id;
    }

    private static void barcode(JsonNode p, String f) {
        String b = text(p, f);
        if (b.length() > MAX_BARCODE_CHARS) throw new IllegalArgumentException(f + " longer than " + MAX_BARCODE_CHARS + " characters");
        if (!BARCODE.matcher(b).matches()) throw new IllegalArgumentException(f + " contains control or invisible characters");
    }

    /** A LocalDateTime PostgreSQL can store; the reason if not, otherwise null. */
    static String timestamp(String value) {
        LocalDateTime t;
        try { t = LocalDateTime.parse(value); }
        catch (RuntimeException e) { return "unparseable"; }
        if (t.getYear() < MIN_YEAR || t.getYear() > MAX_YEAR) return "year must be between " + MIN_YEAR + " and " + MAX_YEAR;
        return null;
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
        String problem = timestamp(n.asText());
        if (problem != null) throw new IllegalArgumentException("Bad " + f + " (" + problem + ")");
    }
}
