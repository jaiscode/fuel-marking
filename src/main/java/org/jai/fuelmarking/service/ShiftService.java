package org.jai.fuelmarking.service;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.*;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ShiftService {
    private final ShiftDao shifts = new ShiftDao();
    private final ContainerDao containers = new ContainerDao();
    private final SignatureDao signatures = new SignatureDao();

    public java.util.Optional<Shift> findOpen(UUID siteId) {
        return Tx.call(c -> shifts.findOpen(c, siteId));
    }

    public Shift start(AppUser supervisor, Site site) {
        Guard.require(supervisor, Role.SUPERVISOR);
        return Tx.call(c -> {
            if (shifts.findOpen(c, site.id()).isPresent())
                throw new IllegalStateException("A shift is already open at " + site.name());
            return shifts.insert(c, site.id(), supervisor.id(), containers.openedVolume(c, site.id()));
        });
    }

    /** Computes the reconciliation as of now without closing the shift. */
    public Reconciliation reconcile(Shift shift) {
        return Tx.call(c -> compute(c, shift));
    }

    /** Closes the shift; a signature is optional (none is asked for on screen) and is kept when given. */
    public Reconciliation end(AppUser supervisor, Shift shift, String notes) { return end(supervisor, shift, null, notes); }

    public Reconciliation end(AppUser supervisor, Shift shift, byte[] signature, String notes) {
        Guard.require(supervisor, Role.SUPERVISOR);
        return Tx.call(c -> {
            if (!"OPEN".equals(shift.status())) throw new IllegalStateException("Shift already closed");
            List<String> blockers = blockers(c, shift);
            if (!blockers.isEmpty()) throw new IllegalStateException("The shift cannot be closed yet: " + String.join("; ", blockers));
            Reconciliation r = compute(c, shift);
            Shift closed = shifts.close(c, shift.id(), r.actualClosingMl(), r.varianceMl(), notes);
            if (signature != null) signatures.save(c, "SHIFT", shift.id(), "SHIFT_CLOSE", supervisor.id(), signature);
            return r;
        });
    }

    /**
     * What still stands in the way of closing the shift (empty when it can be closed): every marking must be verified, and every opened
     * jerrican must be closed away - closed back with new seals if it still holds rum, or declared empty if it is used up.
     */
    public List<String> closeBlockers(Shift shift) {
        return Tx.call(c -> blockers(c, shift));
    }

    private List<String> blockers(java.sql.Connection c, Shift shift) throws SQLException {
        List<String> out = new ArrayList<>();
        long pending = countUnverified(c, shift.id());
        if (pending > 0) out.add(pending + (pending == 1 ? " marking is" : " markings are") + " not yet verified");
        List<String> open = containers.listBySite(c, shift.siteId()).stream()
                .filter(k -> !k.type().isBulk() && "OPENED".equals(k.status())).map(Container::barcode).toList();
        if (!open.isEmpty()) out.add((open.size() == 1 ? "jerrican " : open.size() + " jerricans ") + String.join(", ", open)
                + (open.size() == 1 ? " is" : " are") + " still open - close " + (open.size() == 1 ? "it" : "them")
                + " back with a seal, or declare " + (open.size() == 1 ? "it" : "them") + " empty");
        return out;
    }

    /** Markings of the shift that are not verified yet; the shift cannot be closed while there are any. */
    public long unverifiedMarkings(Shift shift) {
        return Tx.call(c -> countUnverified(c, shift.id()));
    }

    private long countUnverified(java.sql.Connection c, UUID shiftId) throws SQLException {
        try (var ps = c.prepareStatement("SELECT count(*) FROM truck_markings WHERE shift_id=? AND status NOT IN ('COMPLETED', 'DELETED')")) {
            ps.setObject(1, shiftId);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private Reconciliation compute(java.sql.Connection c, Shift shift) throws SQLException {
        double opened = containers.volumeOpenedSince(c, shift.siteId(), shift.startedAt());
        // every dose that left a jerrican for a truck of this shift - including the first dose of a truck that still has another rum type to take
        double dispensed = scalar(c, "SELECT COALESCE(SUM(d.measured_g),0) FROM marking_doses d JOIN truck_markings m ON m.id = d.truck_marking_id WHERE m.shift_id=?", shift.id());
        double incidents = scalar(c, "SELECT COALESCE(SUM(quantity_ml),0) FROM incidents WHERE shift_id=? AND container_id IS NOT NULL", shift.id());
        double closedBack = containers.volumeClosedBackSince(c, shift.siteId(), shift.startedAt());
        double expected = shift.openingRumMl() + opened - dispensed - incidents - closedBack;
        double actual = containers.openedVolume(c, shift.siteId());
        return new Reconciliation(shift.openingRumMl(), opened, dispensed, incidents, closedBack, expected, actual, actual - expected);
    }

    private double scalar(java.sql.Connection c, String sql, UUID id) throws SQLException {
        try (var ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getDouble(1); }
        }
    }
}
