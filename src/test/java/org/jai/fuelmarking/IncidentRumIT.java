package org.jai.fuelmarking;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;
import org.jai.fuelmarking.service.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** An incident records which rum was lost - against a real Postgres. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncidentRumIT {
    static final byte[] SIG = {1, 2, 3};
    static final String N = Long.toString(System.nanoTime() % 1_000_000, 36);
    static final Site site = new Site(UUID.randomUUID(), "IR" + N, "Incident Site " + N, "FIXED");
    static final IncidentService incidents = new IncidentService();
    static final ContainerService containers = new ContainerService();
    static AppUser sup, wh, dosing;
    static Shift shift;

    static AppUser user(java.sql.Connection c, String name, Role r) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), name + N, name, r, site.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, u.username()); ps.setString(3, name); ps.setString(4, r.name()); ps.setObject(5, site.id());
            ps.executeUpdate();
        }
        return u;
    }

    static String rumOf(UUID incidentId) {
        return Tx.call(c -> {
            try (var ps = c.prepareStatement("SELECT rum_type_code FROM incidents WHERE id = ?")) {
                ps.setObject(1, incidentId);
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
            }
        });
    }

    @BeforeAll
    static void seed() {
        Tx.run(c -> {
            new MasterDataDao().upsertSite(c, site);
            sup = user(c, "sup", Role.SUPERVISOR); wh = user(c, "wh", Role.WAREHOUSE_MANAGER); dosing = user(c, "dose", Role.DOSING_OPERATOR);
            return null;
        });
        Fixtures.ibc(containers, wh, site, "IRSRC-" + N, 900_000);
        Fixtures.plastic(containers, wh, site, "IRJ-" + N, 22_000, 20_000, "IRSRC-" + N);
        Fixtures.plastic(containers, wh, site, "IRK-" + N, 22_000, 10_000, "IRSRC-" + N);
        shift = new ShiftService().start(sup, site);
        containers.openJerrican(sup, site, shift, "IRJ-" + N, Fixtures.seal("IRJ-" + N), "", SIG);
        containers.openJerrican(sup, site, shift, "IRK-" + N, Fixtures.seal("IRK-" + N), "", SIG);
    }

    @Test @Order(1)
    void withAContainerNamedTheRumLostIsTheRumItHolds() {
        Incident i = incidents.report(dosing, site, shift, "IRJ-" + N, "SPILLAGE", 25, "dropped");
        assertEquals("STANDARD", i.rumTypeCode());
        assertEquals("STANDARD", rumOf(i.id()));
        assertEquals(19_975, containers.find("IRJ-" + N).orElseThrow().currentVolumeMl(), 0.0);
        // naming the rum as well is fine when it agrees, and refused when it does not
        assertEquals("STANDARD", incidents.report(dosing, site, shift, "IRJ-" + N, "STANDARD", "LEAKAGE", 5, null).rumTypeCode());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> incidents.report(dosing, site, shift, "IRJ-" + N, "131001", "LEAKAGE", 5, null)).getMessage().contains("holds rum type STANDARD, not 131001"));
        assertEquals(19_970, containers.find("IRJ-" + N).orElseThrow().currentVolumeMl(), 0.0, "the refused incident changed nothing");
    }

    @Test @Order(2)
    void withNoContainerTheRumTypeMustBeChosenFromTheMasterList() {
        Incident i = incidents.report(dosing, site, shift, null, "STANDARD", "SPILLAGE", 12.5, "spilled on the floor");
        assertNull(i.containerId());
        assertEquals("STANDARD", rumOf(i.id()));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> incidents.report(dosing, site, shift, null, "SPILLAGE", 12.5, "x")).getMessage().contains("Say which rum was lost"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> incidents.report(dosing, site, shift, "", "  ", "SPILLAGE", 12.5, "x")).getMessage().contains("Say which rum was lost"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> incidents.report(dosing, site, shift, null, "NOPE", "SPILLAGE", 12.5, "x")).getMessage().contains("Unknown rum type NOPE"));
    }

    @Test @Order(3)
    void incidentsRaisedByDeclaringEmptyAndByReconcilingCarryTheRumToo() {
        var reconcile = new ReconcileService();
        StockCount sc = reconcile.submit(sup, site, shift, List.of(new StockEntry("IRJ-" + N, 19_000, "MANUAL", "LEAKAGE", "cap was loose"),
                new StockEntry("IRK-" + N, 10_000, "MANUAL")), null, SIG);
        var line = reconcile.lines(sc.id()).stream().filter(l -> l.barcode().equals("IRJ-" + N)).findFirst().orElseThrow();
        assertEquals("STANDARD", rumOf(line.incidentId()), "a shortfall found at reconciliation says which rum");
        containers.declareEmpty(sup, site, shift, "IRK-" + N, "OTHER", "used up", SIG);
        assertEquals(1, Tx.call(c -> {
            try (var ps = c.prepareStatement("SELECT count(*) FROM incidents WHERE container_id = (SELECT id FROM containers WHERE barcode = ?) AND rum_type_code = 'STANDARD' AND description LIKE 'Jerrican declared empty%'")) {
                ps.setString(1, "IRK-" + N);
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
            }
        }).intValue(), "the rum left in a jerrican that is declared empty is recorded with its rum type");
    }

    @Test @Order(4)
    void theRumGoesToHeadOfficeWithTheIncident() {
        String payload = Tx.call(c -> {
            try (var ps = c.prepareStatement("SELECT payload::text FROM sync_outbox WHERE entity_type = 'INCIDENT' ORDER BY id DESC LIMIT 1")) {
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
            }
        });
        assertTrue(payload.contains("\"rumTypeCode\": \"STANDARD\""), payload);
    }
}
