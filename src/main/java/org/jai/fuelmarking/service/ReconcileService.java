package org.jai.fuelmarking.service;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.ContainerDao;
import org.jai.fuelmarking.dao.SignatureDao;
import org.jai.fuelmarking.dao.StockCountDao;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;

import java.time.LocalDateTime;
import java.util.*;

/**
 * The supervisor's reconciliation of the jerricans at the site, at any time during a shift: every OPENED jerrican is weighed or typed and compared
 * with what the system holds. A shortfall must be explained - spillage, leakage or other - and becomes an incident, which brings the system back in
 * line with what is physically there. Extra rum is only noted. The SEALED jerricans are not weighed (that would break their seals): they are counted
 * and each seal is looked at - found with its seal intact, not found, or found with a broken seal. A jerrican that cannot be found is recorded as an
 * incident and marked MISSING; a broken seal is recorded and flagged for head office. The whole check is signed and recorded (and sent to head
 * office) as a stock count of kind SHIFT.
 */
public class ReconcileService {
    /** Differences below this many grams are scale noise, not a loss. */
    public static final double TOLERANCE_G = 1.0;

    private final ContainerDao containers = new ContainerDao();
    private final StockCountDao counts = new StockCountDao();
    private final SignatureDao signatures = new SignatureDao();
    private final IncidentService incidents = new IncidentService();

    /** The opened jerricans at the site: the ones that have to be weighed. */
    public List<Container> toReconcile(UUID siteId) {
        return Tx.call(c -> opened(c, siteId));
    }

    /** The sealed jerricans at the site: the ones that have to be counted, each with a look at its seal. */
    public List<Container> sealedToCheck(UUID siteId) {
        return Tx.call(c -> sealed(c, siteId));
    }

    private List<Container> sealed(java.sql.Connection c, UUID siteId) throws java.sql.SQLException {
        return containers.listBySite(c, siteId).stream().filter(k -> !k.type().isBulk() && "SEALED".equals(k.status())).toList();
    }

    private List<Container> opened(java.sql.Connection c, UUID siteId) throws java.sql.SQLException {
        return containers.listBySite(c, siteId).stream().filter(k -> !k.type().isBulk() && "OPENED".equals(k.status())).toList();
    }

    /** Without a check of the sealed jerricans: only allowed when there are none at the site. */
    public StockCount submit(AppUser supervisor, Site site, Shift shift, List<StockEntry> entries, String comment, byte[] signature) {
        return submit(supervisor, site, shift, entries, null, comment, signature);
    }

    public StockCount submit(AppUser supervisor, Site site, Shift shift, List<StockEntry> entries, List<SealedCheck> sealedChecks, String comment, byte[] signature) {
        Guard.require(supervisor, Role.SUPERVISOR);
        Guard.signed(signature);
        if (shift == null || !"OPEN".equals(shift.status())) throw new IllegalStateException("No open shift");
        if (comment != null && comment.length() > 500) throw new IllegalArgumentException("The comment is too long (500 characters at most)");
        return Tx.call(c -> {
            List<Container> held = opened(c, site.id());
            List<Container> sealed = sealed(c, site.id());
            if (held.isEmpty() && sealed.isEmpty()) throw new IllegalStateException("There is no opened jerrican at " + site.name() + " to reconcile");
            Map<String, StockEntry> byBarcode = new HashMap<>();
            for (StockEntry e : entries) {
                if (byBarcode.put(e.barcode(), e) != null) throw new IllegalArgumentException(e.barcode() + " was counted twice");
                if (Double.isNaN(e.countedG()) || e.countedG() < 0) throw new IllegalArgumentException("The count for " + e.barcode() + " cannot be negative");
                if (!"SCALE".equals(e.source()) && !"MANUAL".equals(e.source())) throw new IllegalArgumentException("Count source must be SCALE or MANUAL");
                if (e.note() != null && e.note().length() > 300) throw new IllegalArgumentException("The note for " + e.barcode() + " is too long (300 characters at most)");
            }
            List<String> missing = held.stream().map(Container::barcode).filter(b -> !byBarcode.containsKey(b)).toList();
            if (!missing.isEmpty()) throw new IllegalArgumentException("Count every opened jerrican - missing: " + String.join(", ", missing));
            Set<String> heldCodes = new HashSet<>();
            held.forEach(k -> heldCodes.add(k.barcode()));
            for (String b : byBarcode.keySet()) if (!heldCodes.contains(b)) throw new IllegalArgumentException(b + " is not an opened jerrican at " + site.name());

            // Check every explanation before anything is written, so a missing cause leaves nothing half done.
            for (Container k : held) {
                StockEntry e = byBarcode.get(k.barcode());
                double diff = e.countedG() - k.currentVolumeMl();
                boolean noted = e.note() != null && !e.note().isBlank();
                if (diff <= -TOLERANCE_G) {
                    if (e.cause() == null || !IncidentService.TYPES.contains(e.cause()))
                        throw new IllegalArgumentException(k.barcode() + " is short - say why: spillage, leakage or other");
                    if ("OTHER".equals(e.cause()) && !noted)
                        throw new IllegalArgumentException(k.barcode() + ": describe what happened (cause is other)");
                } else if (diff >= TOLERANCE_G && !noted) {
                    throw new IllegalArgumentException(k.barcode() + " holds more than the system says - add a note");
                }
            }

            // the sealed jerricans: every one is accounted for, once, and anything but "found, seal intact" says why
            Map<String, SealedCheck> checks = new HashMap<>();
            if (sealedChecks != null) for (SealedCheck sc : sealedChecks) {
                if (checks.put(sc.barcode(), sc) != null) throw new IllegalArgumentException(sc.barcode() + " was checked twice");
                if (!List.of("FOUND", "MISSING", "SEAL_BROKEN").contains(sc.result())) throw new IllegalArgumentException("The check of " + sc.barcode() + " must be FOUND, MISSING or SEAL_BROKEN");
                if (!"FOUND".equals(sc.result()) && (sc.note() == null || sc.note().isBlank()))
                    throw new IllegalArgumentException(sc.barcode() + ": say what you found (" + ("MISSING".equals(sc.result()) ? "where it might be" : "what is wrong with the seal") + ")");
                if (sc.note() != null && sc.note().length() > 300) throw new IllegalArgumentException("The note for " + sc.barcode() + " is too long (300 characters at most)");
            }
            Set<String> sealedCodes = new HashSet<>();
            sealed.forEach(k -> sealedCodes.add(k.barcode()));
            for (String b : checks.keySet()) if (!sealedCodes.contains(b)) throw new IllegalArgumentException(b + " is not a sealed jerrican at " + site.name());
            List<String> unchecked = sealed.stream().map(Container::barcode).filter(b -> !checks.containsKey(b)).toList();
            if (!unchecked.isEmpty()) throw new IllegalArgumentException("Check every sealed jerrican - not checked: " + String.join(", ", unchecked));

            Map<UUID, StockCountLine> lines = new LinkedHashMap<>();
            double system = 0, counted = 0;
            for (Container k : held) {
                StockEntry e = byBarcode.get(k.barcode());
                double diff = e.countedG() - k.currentVolumeMl();
                String note = e.note() == null || e.note().isBlank() ? null : e.note().trim();
                UUID incidentId = null;
                String cause = null;
                if (diff <= -TOLERANCE_G) {
                    cause = e.cause();
                    incidentId = incidents.reportIn(c, supervisor, site, shift, k.barcode(), cause, -diff,
                            "Found at stock reconciliation" + (note == null ? "" : ": " + note)).id();
                }
                lines.put(k.id(), new StockCountLine(k.barcode(), k.type().code(), k.rumTypeCode(), k.currentVolumeMl(), e.countedG(), e.source(),
                        cause, note, incidentId));
                system += k.currentVolumeMl();
                counted += e.countedG();
            }
            for (Container k : sealed) {
                SealedCheck check = checks.get(k.barcode());
                String note = check.note() == null || check.note().isBlank() ? null : check.note().trim();
                UUID incidentId = null;
                String cause = null;
                double countedG = k.currentVolumeMl();
                if ("MISSING".equals(check.result())) {          // not there: the rum in it is lost to the site - an incident, and the jerrican is marked missing
                    countedG = 0;
                    cause = "OTHER";
                    if (k.currentVolumeMl() > 0)
                        incidentId = incidents.reportIn(c, supervisor, site, shift, k.barcode(), "OTHER", k.currentVolumeMl(), "Sealed jerrican not found at the stock check: " + note).id();
                    containers.markMissing(c, k.id());
                }
                lines.put(k.id(), new StockCountLine(k.barcode(), k.type().code(), k.rumTypeCode(), k.currentVolumeMl(), countedG, "MANUAL", cause, note, incidentId, check.result()));
                system += k.currentVolumeMl();
                counted += countedG;
            }
            StockCount sc = new StockCount(UUID.randomUUID(), site.id(), supervisor.id(), LocalDateTime.now().withNano(0),
                    comment == null || comment.isBlank() ? null : comment.trim(), system, counted, "SHIFT", shift.id());
            counts.insert(c, sc, lines);
            signatures.save(c, "STOCK_COUNT", sc.id(), "STOCK_COUNT", supervisor.id(), signature);
            counts.publish(c, sc);
            return sc;
        });
    }

    /** Reconciliations done during the given shift, newest first. */
    public List<StockCount> history(UUID siteId) { return Tx.call(c -> counts.history(c, siteId, "SHIFT", 50)); }

    public List<StockCountLine> lines(UUID stockCountId) { return Tx.call(c -> counts.lines(c, stockCountId)); }
}
