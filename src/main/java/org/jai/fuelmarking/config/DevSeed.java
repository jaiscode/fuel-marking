package org.jai.fuelmarking.config;

import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;
import org.jai.fuelmarking.security.PinHasher;

import java.sql.PreparedStatement;
import java.util.UUID;

/** Development-only sample master data (enable with -Ddev.seed=true). Real data is synced from the web app. */
public final class DevSeed {
    /** Every seeded user signs in with this PIN. Development only. */
    public static final String DEV_PIN = "246810";

    private DevSeed() {}

    public static void runIfEnabled() {
        if (!Boolean.getBoolean("dev.seed")) return;
        Tx.run(c -> {
            MasterDataDao m = new MasterDataDao();
            if (!m.sites(c).isEmpty()) return null;
            Site site = new Site(UUID.randomUUID(), "DEV1", "Dev Marking Site", "FIXED");
            m.upsertSite(c, site);
            m.upsertOmc(c, new Omc(UUID.randomUUID(), "OMC1", "Demo Oil Company"));
            m.upsertOmc(c, new Omc(UUID.randomUUID(), "OMC2", "Sample Petroleum"));
            String[][] users = {{"sup", "Sam Supervisor", "SUPERVISOR"}, {"dose", "Dana Dosing", "DOSING_OPERATOR"},
                    {"disp", "Dev Dispense", "DISPENSE_OPERATOR"}, {"wh", "Wendy Warehouse", "WAREHOUSE_MANAGER"}};
            for (String[] u : users) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO app_users(id, username, full_name, role, site_id, pin_hash) VALUES (?,?,?,?,?,?)")) {
                    ps.setObject(1, UUID.randomUUID()); ps.setString(2, u[0]); ps.setString(3, u[1]);
                    ps.setString(4, Role.valueOf(u[2]).name()); ps.setObject(5, site.id());
                    ps.setString(6, PinHasher.hash(DEV_PIN));
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }
}
