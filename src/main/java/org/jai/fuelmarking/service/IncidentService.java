package org.jai.fuelmarking.service;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.*;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class IncidentService {
    private final IncidentDao incidents = new IncidentDao();
    private final ContainerDao containers = new ContainerDao();

    public static final List<String> TYPES = List.of("SPILLAGE", "LEAKAGE", "OTHER");

    /** Records a spillage, leakage or other loss; if a container is named, its volume is reduced by the lost quantity. */
    public Incident report(AppUser user, Site site, Shift shift, String containerBarcode, String type,
                           double quantityMl, String description) {
        return report(user, site, shift, containerBarcode, null, type, quantityMl, description);
    }

    /**
     * An incident records which rum was lost. With a container named it is that container's rum type; with none, {@code rumTypeCode} says which
     * rum it was (a rum type from the master data).
     */
    public Incident report(AppUser user, Site site, Shift shift, String containerBarcode, String rumTypeCode, String type,
                           double quantityMl, String description) {
        Guard.require(user, Role.SUPERVISOR, Role.DOSING_OPERATOR, Role.DISPENSE_OPERATOR, Role.WAREHOUSE_MANAGER);
        return Tx.call(c -> reportIn(c, user, site, shift, containerBarcode, rumTypeCode, type, quantityMl, description));
    }

    /** The same inside a transaction that is already open (the stock reconciliation raises its incidents with it). */
    Incident reportIn(java.sql.Connection c, AppUser user, Site site, Shift shift, String containerBarcode, String type,
                      double quantityMl, String description) throws java.sql.SQLException {
        return reportIn(c, user, site, shift, containerBarcode, null, type, quantityMl, description);
    }

    Incident reportIn(java.sql.Connection c, AppUser user, Site site, Shift shift, String containerBarcode, String rumTypeCode, String type,
                      double quantityMl, String description) throws java.sql.SQLException {
        if (user.role() == Role.SUPERVISOR && shift == null) throw new IllegalStateException("Start a shift first");
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Type must be SPILLAGE, LEAKAGE or OTHER");
        if (quantityMl <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if ("OTHER".equals(type) && (description == null || description.isBlank()))
            throw new IllegalArgumentException("Say what happened - an incident of type OTHER needs a description");
        String rum = rumTypeCode == null || rumTypeCode.isBlank() ? null : rumTypeCode.trim();
        UUID containerId = null;
        if (containerBarcode != null && !containerBarcode.isBlank()) {
            Container k = containers.findByBarcode(c, containerBarcode, true)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown container " + containerBarcode));
            if (rum != null && k.rumTypeCode() != null && !rum.equals(k.rumTypeCode()))
                throw new IllegalArgumentException("Container " + k.barcode() + " holds rum type " + k.rumTypeCode() + ", not " + rum);
            if (k.rumTypeCode() != null) rum = k.rumTypeCode();                 // the rum lost is the rum the container held
            containers.setVolume(c, k.id(), Math.max(0, k.currentVolumeMl() - quantityMl));
            containerId = k.id();
        }
        if (rum == null) throw new IllegalArgumentException("Say which rum was lost - choose the rum type" + (containerBarcode == null || containerBarcode.isBlank() ? "" : " (container " + containerBarcode + " has none on record)"));
        if (new org.jai.fuelmarking.dao.MasterDataDao().rumType(c, rum).isEmpty()) throw new IllegalArgumentException("Unknown rum type " + rum);
        return incidents.insert(c, site.id(), shift == null ? null : shift.id(), containerId, type, quantityMl, description, user.id(), rum);
    }

    public List<Incident> list(UUID siteId, LocalDateTime from, LocalDateTime to) {
        return Tx.call(c -> incidents.listBySite(c, siteId, from, to));
    }
}
