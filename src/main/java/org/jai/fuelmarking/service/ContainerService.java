package org.jai.fuelmarking.service;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.ContainerDao;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.dao.SignatureDao;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;

import java.util.List;
import java.util.UUID;

public class ContainerService {
    private final ContainerDao containers = new ContainerDao();
    private final SignatureDao signatures = new SignatureDao();
    private final MasterDataDao rumTypes = new MasterDataDao();
    private final IncidentService incidents = new IncidentService();

    /** Receive an IBC or drum into a site's stock, with the rum type it holds. */
    public Container receiveBulk(AppUser user, Site site, String barcode, String typeCode, double capacityG,
                                 double volumeG, String rumTypeCode, String topSeal, String bottomSeal) {
        Guard.require(user, Role.WAREHOUSE_MANAGER);
        if (barcode == null || barcode.isBlank()) throw new IllegalArgumentException("Enter the barcode");
        if (capacityG <= 0) throw new IllegalArgumentException("Capacity must be more than zero");
        if (volumeG < 0 || volumeG > capacityG) throw new IllegalArgumentException("Volume must be between 0 and capacity");
        return Tx.call(c -> {
            ContainerType type = rumTypes.containerType(c, typeCode == null ? "" : typeCode)
                    .orElseThrow(() -> new IllegalArgumentException("Choose the container type"));
            if (!type.isBulk()) throw new IllegalArgumentException("Jerricans are received empty with their registered id; this is for bulk containers such as IBCs and drums");
            if (rumTypes.rumType(c, rumTypeCode == null ? "" : rumTypeCode).isEmpty())
                throw new IllegalArgumentException("Choose the rum type this " + type.name().toLowerCase() + " holds");
            String[] seals = Seals.check(type, topSeal, bottomSeal);
            if (containers.findByBarcode(c, barcode.trim(), false).isPresent())
                throw new IllegalArgumentException("Barcode already registered: " + barcode.trim());
            Container k = new Container(UUID.randomUUID(), barcode.trim(), type, capacityG, volumeG, seals[0], seals[1],
                    "SEALED", site.id(), null, rumTypeCode);
            containers.insert(c, k);
            return k;
        });
    }

    /**
     * Receive an EMPTY jerrican at the warehouse. Head office registers every jerrican (id, material, capacity, rum type) in the
     * web app; the warehouse enters the id and the rum type, and the jerrican is accepted only if it is registered and the
     * rum type matches. The jerrican stays EMPTY until it is refilled and sealed.
     */
    public Container receiveEmptyJerrican(AppUser user, Site site, String barcode, String rumTypeCode) {
        Guard.require(user, Role.WAREHOUSE_MANAGER);
        if (barcode == null || barcode.isBlank()) throw new IllegalArgumentException("Enter the jerrican id");
        String id = barcode.trim();
        return Tx.call(c -> {
            RegisteredJerrican reg = rumTypes.registeredJerrican(c, id).orElseThrow(() -> new IllegalArgumentException(
                    "Jerrican " + id + " is not registered at head office. Register it in the web app first (it reaches the warehouse within seconds)."));
            if (rumTypeCode == null || rumTypeCode.isBlank()) throw new IllegalArgumentException("Choose the rum type");
            if (!reg.rumTypeCode().equals(rumTypeCode))
                throw new IllegalArgumentException("Jerrican " + id + " is registered for rum type " + reg.rumTypeCode() + ", not " + rumTypeCode);
            var existing = containers.findByBarcode(c, id, true);
            if (existing.isPresent()) {
                Container k = existing.get();
                throw new IllegalArgumentException("Jerrican " + id + " is already in the system (" + k.status() + ")");
            }
            Container k = new Container(UUID.randomUUID(), id, reg.type(), reg.capacityG(), 0, null, null,
                    "EMPTY", site.id(), null, reg.rumTypeCode());
            containers.insert(c, k);
            return k;
        });
    }

    /** What head office says about a jerrican id, for the screens to show before receiving or refilling. */
    public java.util.Optional<RegisteredJerrican> registeredJerrican(String barcode) {
        if (barcode == null || barcode.isBlank()) return java.util.Optional.empty();
        return Tx.call(c -> rumTypes.registeredJerrican(c, barcode.trim()));
    }

    public java.util.Optional<Container> find(String barcode) {
        if (barcode == null || barcode.isBlank()) return java.util.Optional.empty();
        return Tx.call(c -> containers.findByBarcode(c, barcode.trim(), false));
    }

    /** Supervisor opens a sealed jerrican at the start of a shift, after checking the seal numbers. */
    public Container openJerrican(AppUser supervisor, Site site, Shift shift, String barcode,
                                  String topSealSeen, String bottomSealSeen, byte[] signature) {
        Guard.require(supervisor, Role.SUPERVISOR);
        Guard.signed(signature);
        if (shift == null || !"OPEN".equals(shift.status())) throw new IllegalStateException("Start a shift first");
        return Tx.call(c -> {
            Container k = containers.findByBarcode(c, barcode, true)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown container " + barcode));
            if (k.type().isBulk()) throw new IllegalArgumentException("Not a jerrican");
            if (!site.id().equals(k.siteId())) throw new IllegalStateException("Container belongs to another site");
            if (!"SEALED".equals(k.status())) throw new IllegalStateException("Container is " + k.status());
            String[] seen = Seals.check(k.type(), topSealSeen, bottomSealSeen);
            if (!java.util.Objects.equals(k.topSeal(), seen[0]) || !java.util.Objects.equals(k.bottomSeal(), seen[1]))
                throw new IllegalStateException("Seal numbers do not match records - report an incident");
            containers.markOpened(c, k.id(), supervisor.id());
            signatures.save(c, "SHIFT", shift.id(), "OPEN_JERRICAN:" + barcode, supervisor.id(), signature);
            return containers.findById(c, k.id(), false).orElseThrow();
        });
    }

    /**
     * The supervisor declares an opened jerrican finished (EMPTY) so it can be sent back to the warehouse for a refill. If the system
     * still shows rum in it, say what happened to that rum - spillage, leakage or other (a note is needed for other): it is recorded as
     * an incident so the shift's reconciliation still adds up.
     *
     * @param cause what became of the rum still shown in the jerrican; ignored when (almost) nothing is left
     */
    public Container declareEmpty(AppUser supervisor, Site site, Shift shift, String barcode, String cause, String note, byte[] signature) {
        Guard.require(supervisor, Role.SUPERVISOR);
        Guard.signed(signature);
        if (shift == null || !"OPEN".equals(shift.status())) throw new IllegalStateException("Start a shift first");
        return Tx.call(c -> {
            Container k = containers.findByBarcode(c, barcode == null ? "" : barcode.trim(), true)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown container " + barcode));
            if (k.type().isBulk()) throw new IllegalArgumentException("Not a jerrican");
            if (!site.id().equals(k.siteId())) throw new IllegalStateException("Container belongs to another site");
            if (!"OPENED".equals(k.status())) throw new IllegalStateException(k.barcode() + " is " + k.status() + " - only an opened jerrican can be declared empty");
            double left = k.currentVolumeMl();
            if (left >= ReconcileService.TOLERANCE_G) {
                if (cause == null || !IncidentService.TYPES.contains(cause))
                    throw new IllegalArgumentException(kgText(left) + " kg of rum is still shown in " + k.barcode() + " - say what happened to it: spillage, leakage or other");
                if ("OTHER".equals(cause) && (note == null || note.isBlank()))
                    throw new IllegalArgumentException("Describe what happened to the rum that was left (cause is other)");
                incidents.reportIn(c, supervisor, site, shift, k.barcode(), cause, left,
                        "Jerrican declared empty with rum left" + (note == null || note.isBlank() ? "" : ": " + note.trim()));
            } else {
                containers.setVolume(c, k.id(), 0);
            }
            containers.discardJerricanSeals(c, k.id());            // its seals are discarded: it goes back to the warehouse without them
            signatures.save(c, "SHIFT", shift.id(), "EMPTY:" + k.barcode(), supervisor.id(), signature);
            return containers.findById(c, k.id(), false).orElseThrow();
        });
    }

    /**
     * At the end of the shift an opened jerrican that still holds rum is closed back: it gets new seals (the ones it was opened with are
     * broken and cannot be used again) and is SEALED again, ready for the next shift. A jerrican that is used up is declared empty instead.
     */
    public Container closeBack(AppUser supervisor, Site site, Shift shift, String barcode, String topSeal, String bottomSeal, byte[] signature) {
        Guard.require(supervisor, Role.SUPERVISOR);
        Guard.signed(signature);
        if (shift == null || !"OPEN".equals(shift.status())) throw new IllegalStateException("Start a shift first");
        return Tx.call(c -> {
            Container k = containers.findByBarcode(c, barcode == null ? "" : barcode.trim(), true)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown container " + barcode));
            if (k.type().isBulk()) throw new IllegalArgumentException("Not a jerrican");
            if (!site.id().equals(k.siteId())) throw new IllegalStateException("Container belongs to another site");
            if (!"OPENED".equals(k.status())) throw new IllegalStateException(k.barcode() + " is " + k.status() + " - only an opened jerrican can be closed back");
            if (k.currentVolumeMl() < ReconcileService.TOLERANCE_G)
                throw new IllegalStateException(k.barcode() + " is used up - declare it empty instead of closing it back");
            String[] seals = Seals.check(k.type(), topSeal, bottomSeal);
            for (String seal : seals) {
                if (seal == null) continue;
                if (seal.equals(k.topSeal()) || seal.equals(k.bottomSeal()))
                    throw new IllegalStateException("Seal " + seal + " is the seal that was broken when " + k.barcode() + " was opened - fit a new seal");
                var other = containers.sealUsedBy(c, seal, k.id());
                if (other.isPresent()) throw new IllegalStateException("Seal " + seal + " is already on container " + other.get());
                var before = containers.sealDiscardedFrom(c, seal);
                if (before.isPresent()) throw new IllegalStateException("Seal " + seal + " was used before (on " + before.get() + ") and has been discarded - fit a new seal");
            }
            containers.rememberSeals(c, k.id());                 // the seals it was opened with are broken: discarded, and never to be used again
            containers.markClosedBack(c, k.id(), seals[0], seals[1]);
            signatures.save(c, "SHIFT", shift.id(), "CLOSE:" + k.barcode(), supervisor.id(), signature);
            return containers.findById(c, k.id(), false).orElseThrow();
        });
    }

    private static String kgText(double grams) {
        String s = String.format(java.util.Locale.ROOT, "%,.3f", grams / 1000.0);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /**
     * Warehouse: refill an EMPTY jerrican from an IBC/drum and seal it. {@code quantityG} is the weighed rum (net, grams).
     * Seals are mandatory: one for a plastic jerrican, two different ones for a metal jerrican, and a seal number can only ever be
     * on one container. The IBC/drum must hold the rum type the jerrican is registered for.
     */
    public void refill(AppUser user, String jerricanBarcode, String bulkBarcode, double quantityG, String topSeal, String bottomSeal) {
        Guard.require(user, Role.WAREHOUSE_MANAGER);
        if (Double.isNaN(quantityG) || quantityG <= 0) throw new IllegalArgumentException("Quantity must be positive");
        Tx.run(c -> {
            Container bulk = containers.findByBarcode(c, bulkBarcode.trim(), true).orElseThrow(() -> new IllegalArgumentException("Unknown IBC/drum " + bulkBarcode));
            Container jerry = containers.findByBarcode(c, jerricanBarcode.trim(), true).orElseThrow(() -> new IllegalArgumentException(
                    "Unknown jerrican " + jerricanBarcode + " - receive it as an empty jerrican first"));
            if (!bulk.type().isBulk() || jerry.type().isBulk()) throw new IllegalArgumentException("Refill goes from IBC/drum to jerrican");
            if (!"EMPTY".equals(jerry.status())) throw new IllegalStateException("Jerrican " + jerry.barcode() + " is " + jerry.status() + "; only an EMPTY jerrican can be refilled");
            if (!java.util.Objects.equals(bulk.rumTypeCode(), jerry.rumTypeCode()))
                throw new IllegalStateException("Rum type mismatch: " + bulk.barcode() + " holds " + bulk.rumTypeCode() + " but jerrican "
                        + jerry.barcode() + " is for " + jerry.rumTypeCode());
            String[] seals = Seals.check(jerry.type(), topSeal, bottomSeal);
            for (String seal : seals) {
                if (seal == null) continue;
                if (seal.equals(jerry.topSeal()) || seal.equals(jerry.bottomSeal()))
                    throw new IllegalStateException("Seal " + seal + " was the seal on this jerrican before - fit a new seal");
                var other = containers.sealUsedBy(c, seal, jerry.id());
                if (other.isPresent()) throw new IllegalStateException("Seal " + seal + " is already on container " + other.get());
                var before = containers.sealDiscardedFrom(c, seal);
                if (before.isPresent()) throw new IllegalStateException("Seal " + seal + " was used before (on " + before.get() + ") and has been discarded - fit a new seal");
            }
            if (bulk.currentVolumeMl() < quantityG) throw new IllegalStateException("Bulk container holds only " + bulk.currentVolumeMl() + " g");
            if (quantityG > jerry.capacityMl()) throw new IllegalStateException("Exceeds jerrican capacity");
            containers.setVolume(c, bulk.id(), bulk.currentVolumeMl() - quantityG);
            containers.markFilled(c, jerry.id(), quantityG, seals[0], seals[1], bulk.id());
            return null;
        });
    }

    public List<Container> inventory(UUID siteId) {
        return Tx.call(c -> containers.listBySite(c, siteId));
    }

    public List<Container> openJerricans(UUID siteId) {
        return Tx.call(c -> containers.listBySiteAndStatus(c, siteId, "OPENED"));
    }
}
