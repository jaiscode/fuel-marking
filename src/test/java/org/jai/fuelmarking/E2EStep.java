package org.jai.fuelmarking;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;
import org.jai.fuelmarking.service.StockCountService;
import org.jai.fuelmarking.service.ContainerService;
import org.jai.fuelmarking.service.TransferService;
import org.jai.fuelmarking.model.Records.Omc;
import org.jai.fuelmarking.model.Records.Shift;
import org.jai.fuelmarking.sync.SyncAgent;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One step of a manual end-to-end run against a real backend, executed once per desktop database:
 * -De2e.step=warehouse | site | warehouse-final  (see the script in the README of the sync work). Skipped otherwise.
 */
class E2EStep {
    static final byte[] SIG = {1, 2, 3};

    static long count(String sql, Object... args) {
        return Tx.call(c -> {
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
            }
        });
    }

    @Test
    void step() {
        String step = System.getProperty("e2e.step");
        Assumptions.assumeTrue(step != null, "not an end-to-end run");
        UUID whId = UUID.fromString(System.getProperty("e2e.wh")), siteId = UUID.fromString(System.getProperty("e2e.site"));
        String url = System.getProperty("e2e.url");
        Site wh = new Site(whId, "E2EWH", "E2E Warehouse", "WAREHOUSE"), site = new Site(siteId, "E2EST", "E2E Site", "FIXED");
        Tx.run(c -> { // master data normally arrives through Kafka
            new MasterDataDao().upsertSite(c, wh);
            new MasterDataDao().upsertSite(c, site);
            return null;
        });

        switch (step) {
            case "warehouse" -> {
                AppUser mgr = user("e2e-mgr", Role.WAREHOUSE_MANAGER, wh);
                ContainerService containers = new ContainerService();
                Fixtures.ibc(containers, mgr, wh, "E2E-SRC1", 900_000);
                Fixtures.plastic(containers, mgr, wh, "E2E-J1", 1000, 1000, "E2E-SRC1");
                new TransferService().dispatch(mgr, wh, site, List.of("E2E-J1"), SIG);
                // the warehouse manager reconciles the stock of its IBC (0.5 kg short) and the count goes to head office with the rest
                double held = containers.find("E2E-SRC1").orElseThrow().currentVolumeMl();
                new StockCountService().submit(mgr, wh, List.of(new org.jai.fuelmarking.model.Records.StockEntry("E2E-SRC1", held - 500, "MANUAL")), "e2e count", SIG);
                assertTrue(new SyncAgent(url, whId, System.getProperty("e2e.key")).syncOnce());
                assertEquals(0, count("SELECT count(*) FROM sync_outbox WHERE synced_at IS NULL"));
                assertEquals(0, count("SELECT count(*) FROM sync_outbox WHERE last_error IS NOT NULL"), "central rejected something");
            }
            case "site" -> {
                AppUser sup = user("e2e-sup", Role.SUPERVISOR, site);
                SyncAgent agent = new SyncAgent(url, siteId, System.getProperty("e2e.key"));
                assertTrue(agent.syncOnce());
                TransferService transfers = new TransferService();
                List<Transfer> incoming = transfers.incoming(siteId);
                assertEquals(1, incoming.size(), "the dispatched transfer should have been pulled");
                new org.jai.fuelmarking.service.ShiftService().start(sup, site);        // a supervisor works with containers during a shift only
                transfers.receive(sup, incoming.get(0), List.of("E2E-J1"), SIG);
                assertTrue(agent.syncOnce());
                assertEquals(0, count("SELECT count(*) FROM sync_outbox WHERE synced_at IS NULL"));
                assertEquals(0, count("SELECT count(*) FROM sync_outbox WHERE last_error IS NOT NULL"), "central rejected something");
                assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode='E2E-J1' AND status='SEALED' AND site_id=?", siteId));
            }
            case "dose" -> {
                // a complete weighed dose at a site, pushed to the real central system
                AppUser sup = user("e2e-dsup", Role.SUPERVISOR, site), dos = user("e2e-ddos", Role.DOSING_OPERATOR, site), mgr = user("e2e-dmgr", Role.WAREHOUSE_MANAGER, site);
                Omc omc = new Omc(UUID.randomUUID(), "E2EOMC", "E2E Oil");
                Tx.run(c -> { new MasterDataDao().upsertOmc(c, omc); return null; });
                Fixtures.ibc(new ContainerService(), mgr, site, "E2E-SRC2", 900_000);
                Fixtures.plastic(new ContainerService(), mgr, site, "E2E-DJ", 1000, 1000, "E2E-SRC2");
                var shiftService = new org.jai.fuelmarking.service.ShiftService();
                Shift shift = shiftService.findOpen(site.id()).orElseGet(() -> shiftService.start(sup, site));
                new ContainerService().openJerrican(sup, site, shift, "E2E-DJ", Fixtures.seal("E2E-DJ"), "", SIG);
                var marking = new org.jai.fuelmarking.service.MarkingService();
                var t = marking.register(sup, site, shift, omc, "E2E-TRUCK", "DIESEL", 10_000, "STANDARD", List.of(), SIG);
                assertEquals(99.5, marking.dose(dos, t.getUuid(), "E2E-DJ", 99.5, "SCALE", SIG));
                assertTrue(new SyncAgent(url, siteId, System.getProperty("e2e.key")).syncOnce());
                assertEquals(0, count("SELECT count(*) FROM sync_outbox WHERE synced_at IS NULL"));
                assertEquals(0, count("SELECT count(*) FROM sync_outbox WHERE last_error IS NOT NULL"), "central rejected something");
            }
            case "warehouse-final" -> {
                assertTrue(new SyncAgent(url, whId, System.getProperty("e2e.key")).syncOnce());
                assertEquals(1, count("SELECT count(*) FROM transfers WHERE status='RECEIVED'"));
                assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode='E2E-J1' AND status='SEALED' AND site_id=?", siteId),
                        "the warehouse should see the container now held by the site");
                assertEquals(1, count("SELECT count(*) FROM transfer_items WHERE received_at IS NOT NULL"));
            }
            default -> fail("unknown step " + step);
        }
    }

    static AppUser user(String name, Role role, Site s) {
        AppUser u = new AppUser(UUID.randomUUID(), name, name, role, s.id());
        Tx.run(c -> {
            try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
                ps.setObject(1, u.id()); ps.setString(2, name); ps.setString(3, name); ps.setString(4, role.name()); ps.setObject(5, s.id());
                ps.executeUpdate();
            }
            return null;
        });
        return u;
    }
}
