package org.jai.fuelmarking;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.MarkingStatus;
import org.jai.fuelmarking.model.TruckMarking;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;
import org.jai.fuelmarking.service.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ending a shift: every marking verified and every opened jerrican closed away - closed back with a new seal when it still holds rum.
 * Against a real Postgres.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloseBackIT {
    static final byte[] SIG = {1, 2, 3};
    static final String N = Long.toString(System.nanoTime() % 1_000_000, 36);
    static final Site site = new Site(UUID.randomUUID(), "CB" + N, "Close Site " + N, "FIXED");
    static final Site other = new Site(UUID.randomUUID(), "CO" + N, "Close Other " + N, "FIXED");
    static final Omc omc = new Omc(UUID.randomUUID(), "CO" + N, "Close Oil " + N);
    static final ContainerService containers = new ContainerService();
    static final ShiftService shifts = new ShiftService();
    static final MarkingService markings = new MarkingService();
    static AppUser sup, wh, dosing, dispense, supElsewhere;
    static Shift shift;

    static AppUser user(java.sql.Connection c, Site s, String name, Role r) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), name + N, name, r, s.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, u.username()); ps.setString(3, name); ps.setString(4, r.name()); ps.setObject(5, s.id());
            ps.executeUpdate();
        }
        return u;
    }

    static Container get(String b) { return containers.find(b).orElseThrow(); }

    @BeforeAll
    static void seed() {
        Tx.run(c -> {
            var m = new MasterDataDao();
            m.upsertSite(c, site); m.upsertSite(c, other); m.upsertOmc(c, omc);
            sup = user(c, site, "sup", Role.SUPERVISOR); wh = user(c, site, "wh", Role.WAREHOUSE_MANAGER);
            dosing = user(c, site, "dose", Role.DOSING_OPERATOR); dispense = user(c, site, "disp", Role.DISPENSE_OPERATOR);
            supElsewhere = user(c, other, "sup2", Role.SUPERVISOR);
            return null;
        });
        Fixtures.ibc(containers, wh, site, "CBSRC-" + N, 900_000);
        for (String j : List.of("A", "B", "C")) Fixtures.plastic(containers, wh, site, "CB" + j + "-" + N, 22_000, 20_000, "CBSRC-" + N);
        shift = shifts.start(sup, site);
        containers.openJerrican(sup, site, shift, "CBA-" + N, Fixtures.seal("CBA-" + N), "", SIG);
        containers.openJerrican(sup, site, shift, "CBB-" + N, Fixtures.seal("CBB-" + N), "", SIG);
    }

    @Test @Order(1)
    void aJerricanCanOnlyBeClosedBackWhenItIsOpenAndHoldsRumAndGetsANewSeal() {
        assertThrows(IllegalStateException.class, () -> containers.closeBack(wh, site, shift, "CBA-" + N, "N1", null, SIG), "supervisors only");
        assertThrows(IllegalArgumentException.class, () -> containers.closeBack(sup, site, shift, "CBA-" + N, "N1", null, null), "unsigned");
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.closeBack(sup, site, null, "CBA-" + N, "N1", null, SIG)).getMessage().contains("Start a shift"));
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.closeBack(sup, site, shift, "CBC-" + N, "N1", null, SIG)).getMessage().contains("only an opened jerrican"), "CBC is still sealed");
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.closeBack(supElsewhere, other, shift, "CBA-" + N, "N1", null, SIG)).getMessage().contains("another site"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.closeBack(sup, site, shift, "CBSRC-" + N, "N1", null, SIG)).getMessage().contains("Not a jerrican"));
        assertThrows(IllegalArgumentException.class, () -> containers.closeBack(sup, site, shift, "NOPE-" + N, "N1", null, SIG));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.closeBack(sup, site, shift, "CBA-" + N, "", null, SIG)).getMessage().contains("Enter the seal number"), "a seal is mandatory");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.closeBack(sup, site, shift, "CBA-" + N, "N1", "N2", SIG)).getMessage().contains("only one seal"));
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.closeBack(sup, site, shift, "CBA-" + N, Fixtures.seal("CBA-" + N), null, SIG)).getMessage().contains("was broken"),
                "the seal it was opened with is broken and cannot go on again");
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.closeBack(sup, site, shift, "CBA-" + N, Fixtures.seal("CBC-" + N), null, SIG)).getMessage().contains("already on container CBC-" + N),
                "a seal number is only ever on one container");
        assertEquals("OPENED", get("CBA-" + N).status(), "every refusal left it as it was");
        Tx.run(c -> { try (var ps = c.prepareStatement("UPDATE containers SET current_volume_ml = 0.3 WHERE barcode = ?")) { ps.setString(1, "CBB-" + N); ps.executeUpdate(); } return null; });
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.closeBack(sup, site, shift, "CBB-" + N, "N9", null, SIG)).getMessage().contains("declare it empty"), "used up: declare it empty");
        Tx.run(c -> { try (var ps = c.prepareStatement("UPDATE containers SET current_volume_ml = 20000 WHERE barcode = ?")) { ps.setString(1, "CBB-" + N); ps.executeUpdate(); } return null; });
    }

    @Test @Order(2)
    void theShiftCannotBeClosedWhileATruckIsUnverifiedOrAJerricanIsOpen() {
        {
            TruckMarking t = markings.register(sup, site, shift, omc, "cb-1-" + N, "DIESEL", 10_000, "STANDARD", List.of(), SIG);
            assertEquals(MarkingStatus.REGISTERED, t.getStatus());
            List<String> blockers = shifts.closeBlockers(shift);
            assertEquals(2, blockers.size(), blockers.toString());
            assertTrue(blockers.get(0).contains("1 marking is not yet verified"), blockers.toString());
            assertTrue(blockers.get(1).contains("2 jerricans CBA-" + N + ", CBB-" + N + " are still open"), blockers.toString());
            String msg = assertThrows(IllegalStateException.class, () -> shifts.end(sup, shift, SIG, null)).getMessage();
            assertTrue(msg.contains("cannot be closed yet") && msg.contains("not yet verified") && msg.contains("still open"), msg);

            markings.dose(dosing, t.getUuid(), "CBA-" + N, 100.0, "MANUAL", SIG);
            markings.dispense(dispense, t.getUuid(), SIG);
            markings.verify(sup, t.getUuid(), SIG);
            assertEquals(1, shifts.closeBlockers(shift).size(), "verified - only the open jerricans are left");
        }
    }

    @Test @Order(3)
    void closingBackSealsItAgainWithItsRumAndTheShiftStillAddsUp() {
        Container a = containers.closeBack(sup, site, shift, "CBA-" + N, "NEW-A-" + N, null, SIG);
        assertEquals("SEALED", a.status());
        assertEquals(1, ReconcileIT.num("SELECT count(*) FROM discarded_seals WHERE seal = ?", Fixtures.seal("CBA-" + N)), 0.0, "the seal it was opened with is broken and remembered");
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.closeBack(sup, site, shift, "CBB-" + N, Fixtures.seal("CBA-" + N), null, SIG)).getMessage().contains("used before"),
                "a discarded seal number cannot go on any container");
        assertEquals(19_900, a.currentVolumeMl(), 0.0, "contents unchanged: 20 000 g less the 100 g dosed");
        assertEquals("NEW-A-" + N, a.topSeal());
        assertEquals(1, shifts.closeBlockers(shift).size());
        containers.declareEmpty(sup, site, shift, "CBB-" + N, "SPILLAGE", "emptied into the funnel", SIG);
        assertTrue(shifts.closeBlockers(shift).isEmpty(), "everything closed away");
        Reconciliation r = shifts.reconcile(shift);
        assertEquals(40_000, r.openedDuringShiftMl(), 0.001);
        assertEquals(100, r.dispensedMl(), 0.001);
        assertEquals(20_000, r.incidentMl(), 0.001, "CBB's 20 000 g left, accounted for as an incident");
        assertEquals(19_900, r.closedBackMl(), 0.001);
        assertEquals(0, r.actualClosingMl(), 0.001);
        assertEquals(0, r.varianceMl(), 0.001);
        Reconciliation closed = shifts.end(sup, shift, "all closed away");      // no signature is asked for
        assertEquals(0, ReconcileIT.num("SELECT count(*) FROM signatures WHERE step = 'SHIFT_CLOSE' AND entity_id = ?", shift.id()), 0.0);
        assertEquals(0, closed.varianceMl(), 0.001);
        assertTrue(shifts.findOpen(site.id()).isEmpty());
    }

    @Test @Order(4)
    void theNextShiftOpensItWithTheNewSealOnlyAndItsRumIsCountedFromThere() {
        Shift next = shifts.start(sup, site);
        assertEquals(0, next.openingRumMl(), 0.001, "nothing was left open overnight");
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.openJerrican(sup, site, next, "CBA-" + N, Fixtures.seal("CBA-" + N), "", SIG)).getMessage().contains("do not match"),
                "the old, broken seal number is wrong now");
        Container a = containers.openJerrican(sup, site, next, "CBA-" + N, "NEW-A-" + N, "", SIG);
        assertEquals("OPENED", a.status());
        Reconciliation r = shifts.reconcile(next);
        assertEquals(19_900, r.openedDuringShiftMl(), 0.001, "what was in it when it was opened again");
        assertEquals(0, r.closedBackMl(), 0.001, "last shift's closing back is not this shift's");
        assertEquals(0, r.varianceMl(), 0.001);

        // opened, closed back and opened again within one shift: counted once, still adding up
        containers.closeBack(sup, site, next, "CBA-" + N, "NEW2-A-" + N, null, SIG);
        containers.openJerrican(sup, site, next, "CBA-" + N, "NEW2-A-" + N, "", SIG);
        Reconciliation again = shifts.reconcile(next);
        assertEquals(39_800, again.openedDuringShiftMl(), 0.001);
        assertEquals(19_900, again.closedBackMl(), 0.001);
        assertEquals(0, again.varianceMl(), 0.001);
        assertEquals(1, shifts.closeBlockers(next).size());
    }
}
