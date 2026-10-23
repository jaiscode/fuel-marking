package org.jai.fuelmarking;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.*;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.service.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end workflow against a real Postgres: run with -Ddatabase.url=... */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkflowIT {
    static final byte[] SIG = {1, 2, 3};
    static Site site = new Site(UUID.randomUUID(), "SITE1", "Test Site", "FIXED");
    static Omc omc = new Omc(UUID.randomUUID(), "OMC1", "Test Oil Co");
    static AppUser sup, dosing, dispense, wh;

    static final ShiftService shifts = new ShiftService();
    static final ContainerService containers = new ContainerService();
    static final MarkingService markings = new MarkingService();
    static final IncidentService incidents = new IncidentService();

    static AppUser user(java.sql.Connection c, String name, Role r) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), name, name, r, site.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, name); ps.setString(3, name); ps.setString(4, r.name()); ps.setObject(5, site.id());
            ps.executeUpdate();
        }
        return u;
    }

    @BeforeAll
    static void seed() {
        Tx.run(c -> {
            MasterDataDao m = new MasterDataDao();
            m.upsertSite(c, site); m.upsertOmc(c, omc);
            sup = user(c, "sup", Role.SUPERVISOR); dosing = user(c, "dose", Role.DOSING_OPERATOR);
            dispense = user(c, "disp", Role.DISPENSE_OPERATOR); wh = user(c, "wh", Role.WAREHOUSE_MANAGER);
            return null;
        });
    }

    static Shift shift;
    static TruckMarking truck;

    @Test @Order(1)
    void receiveRefillAndOpen() {
        Fixtures.ibc(containers, wh, site, "IBC-1", 1_000_000);
        Fixtures.plastic(containers, wh, site, "JC-1", 1000, 1000, "IBC-1");
        shift = shifts.start(sup, site);
        assertThrows(IllegalStateException.class, () -> shifts.start(sup, site));
        // the jerrican was refilled after receipt, so it is still sealed: wrong seals must be rejected
        assertThrows(IllegalStateException.class,
                () -> containers.openJerrican(sup, site, shift, "JC-1", "XX", "", SIG));
        Container k = containers.openJerrican(sup, site, shift, "JC-1", Fixtures.seal("JC-1"), "", SIG);
        assertEquals("OPENED", k.status());
        assertEquals(1000, k.currentVolumeMl(), 0.001);
    }

    @Test @Order(2)
    void registerDoseDispenseVerify() {
        truck = markings.register(sup, site, shift, omc, "ab-123", "DIESEL", 10_000, "STANDARD",
                List.of(new CompartmentDetail("C1", 6000, 6000), new CompartmentDetail("C2", 5000, 4000)), SIG);
        assertEquals(MarkingStatus.REGISTERED, truck.getStatus());
        assertThrows(IllegalStateException.class, () -> markings.dispense(dispense, truck.getUuid(), SIG)); // not dosed yet
        assertThrows(IllegalStateException.class, () -> markings.dose(sup, truck.getUuid(), "JC-1", 100.0, "SCALE", SIG));  // wrong role
        // two compartments (6 000 L and 4 000 L), each dosed on its own against its own target
        assertEquals(60.0, markings.dose(dosing, truck.getUuid(), "C1", "JC-1", 60.0, "SCALE", SIG), 0.001);
        assertEquals(MarkingStatus.REGISTERED, markings.byShift(shift.id(), MarkingStatus.REGISTERED).get(0).getStatus(), "C2 is still to dose");
        assertThrows(IllegalStateException.class, () -> markings.dispense(dispense, truck.getUuid(), SIG));
        assertEquals(40.0, markings.dose(dosing, truck.getUuid(), "C2", "JC-1", 40.0, "SCALE", SIG), 0.001);   // 10 000 L -> 100 g in all
        markings.dispense(dispense, truck.getUuid(), SIG);
        assertThrows(IllegalStateException.class, () -> shifts.end(sup, shift, SIG, null)); // unverified marking
        markings.verify(sup, truck.getUuid(), SIG);
        assertEquals(1, markings.byShift(shift.id(), MarkingStatus.COMPLETED).size());
    }

    @Test @Order(3)
    void incidentAndReconciliation() {
        incidents.report(dosing, site, shift, "JC-1", "SPILLAGE", 20, "dropped");
        Reconciliation r = shifts.reconcile(shift);
        assertEquals(1000, r.openedDuringShiftMl(), 0.001);
        assertEquals(100, r.dispensedMl(), 0.001);
        assertEquals(20, r.incidentMl(), 0.001);
        assertEquals(880, r.actualClosingMl(), 0.001);
        assertEquals(0, r.varianceMl(), 0.001);
        // the shift cannot close while the jerrican is still open: it is closed back with a new seal first
        assertTrue(assertThrows(IllegalStateException.class, () -> shifts.end(sup, shift, SIG, "ok")).getMessage().contains("jerrican JC-1 is still open"));
        assertEquals(List.of("jerrican JC-1 is still open - close it back with a seal, or declare it empty"), shifts.closeBlockers(shift));
        containers.closeBack(sup, site, shift, "JC-1", "NEW-JC-1", null, SIG);
        assertTrue(shifts.closeBlockers(shift).isEmpty());
        Reconciliation closed = shifts.reconcile(shift);
        assertEquals(880, closed.closedBackMl(), 0.001, "the rum put back in sealed stock");
        assertEquals(0, closed.actualClosingMl(), 0.001);
        assertEquals(0, closed.varianceMl(), 0.001);
        shifts.end(sup, shift, SIG, "ok");
        assertTrue(shifts.findOpen(site.id()).isEmpty());
    }

    @Test @Order(4)
    void everyWriteWasQueuedForSync() {
        long pending = Tx.call(c -> {
            try (var rs = c.createStatement().executeQuery("SELECT count(*) FROM sync_outbox WHERE synced_at IS NULL")) {
                rs.next(); return rs.getLong(1);
            }
        });
        assertTrue(pending > 10, "outbox rows: " + pending);
    }

}
