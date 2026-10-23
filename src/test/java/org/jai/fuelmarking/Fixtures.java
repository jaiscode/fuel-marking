package org.jai.fuelmarking;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.service.ContainerService;

import java.util.UUID;

/** Test set-up through the real workflow: head office registers a jerrican, the warehouse receives it empty and refills it. */
public final class Fixtures {
    private Fixtures() {}

    /** What the web app does: register a jerrican (arrives at the site as master data). */
    public static void register(String barcode, String containerTypeCode, double capacityG, String rumTypeCode) {
        Tx.run(c -> {
            try (var ps = c.prepareStatement("""
                    INSERT INTO registered_jerricans(id, barcode, container_type_code, capacity_g, rum_type_code) VALUES (?,?,?,?,?)
                    ON CONFLICT (barcode) DO UPDATE SET container_type_code=EXCLUDED.container_type_code, capacity_g=EXCLUDED.capacity_g,
                        rum_type_code=EXCLUDED.rum_type_code, active=true""")) {
                ps.setObject(1, UUID.randomUUID()); ps.setString(2, barcode); ps.setString(3, containerTypeCode);
                ps.setDouble(4, capacityG); ps.setString(5, rumTypeCode);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public static Container ibc(ContainerService s, AppUser wh, Site site, String barcode, double volumeG) {
        return s.receiveBulk(wh, site, barcode, "IBC", 1_000_000, volumeG, "STANDARD", "IT-" + barcode, "IB-" + barcode);
    }

    /** A plastic jerrican (one seal, "S-barcode") registered, received empty and refilled with {@code grams} of rum from the IBC. */
    public static Container plastic(ContainerService s, AppUser wh, Site site, String barcode, double capacityG, double grams, String bulkBarcode) {
        register(barcode, "JERRICAN_PLASTIC", capacityG, "STANDARD");
        s.receiveEmptyJerrican(wh, site, barcode, "STANDARD");
        s.refill(wh, barcode, bulkBarcode, grams, "S-" + barcode, null);
        return s.find(barcode).orElseThrow();
    }

    /** The seal to quote when opening a jerrican made by {@link #plastic}. */
    public static String seal(String barcode) { return "S-" + barcode; }
}
