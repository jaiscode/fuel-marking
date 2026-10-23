package org.jai.fuelmarking;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.CompartmentDetail;
import org.jai.fuelmarking.model.MarkingStatus;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;
import org.jai.fuelmarking.model.TruckMarking;
import org.jai.fuelmarking.service.*;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A marking can be deleted before its first dose - and not after - against a real Postgres. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeleteMarkingIT {
    static final byte[] SIG = {1, 2, 3};
    static final String N = Long.toString(System.nanoTime() % 1_000_000, 36);
    static final Site site = new Site(UUID.randomUUID(), "DM" + N, "Delete Site " + N, "FIXED");
    static final Site other = new Site(UUID.randomUUID(), "DO" + N, "Delete Other " + N, "FIXED");
    static final Omc omc = new Omc(UUID.randomUUID(), "DM" + N, "Delete Oil " + N);
    static final MarkingService markings = new MarkingService();
    static final ShiftService shifts = new ShiftService();
    static final ContainerService containers = new ContainerService();
    static AppUser sup, supElsewhere, dosing, dispense, wh;
    static Shift shift;

    static AppUser user(java.sql.Connection c, Site s, String name, Role r) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), name + N, name, r, s.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, u.username()); ps.setString(3, name); ps.setString(4, r.name()); ps.setObject(5, s.id());
            ps.executeUpdate();
        }
        return u;
    }

    static TruckMarking truck(String plate) {
        return markings.register(sup, site, shift, omc, plate + N, "x",
                List.of(new CompartmentDetail("C1", 6000, 6000, "DIESEL", null), new CompartmentDetail("C2", 4000, 4000, "PETROL", null)), SIG);
    }

    static TruckMarking reload(TruckMarking t) {
        return markings.byShift(shift.id(), null).stream().filter(x -> x.getUuid().equals(t.getUuid())).findFirst().orElse(null);
    }

    static String status(TruckMarking t) {
        return Tx.call(c -> {
            try (var ps = c.prepareStatement("SELECT status FROM truck_markings WHERE uuid = ?")) {
                ps.setObject(1, t.getUuid());
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
            }
        });
    }

    @BeforeAll
    static void seed() {
        Tx.run(c -> {
            var m = new MasterDataDao();
            m.upsertSite(c, site); m.upsertSite(c, other); m.upsertOmc(c, omc);
            sup = user(c, site, "sup", Role.SUPERVISOR); supElsewhere = user(c, other, "sup2", Role.SUPERVISOR);
            dosing = user(c, site, "dose", Role.DOSING_OPERATOR); dispense = user(c, site, "disp", Role.DISPENSE_OPERATOR); wh = user(c, site, "wh", Role.WAREHOUSE_MANAGER);
            return null;
        });
        Fixtures.ibc(containers, wh, site, "DMSRC-" + N, 900_000);
        Fixtures.plastic(containers, wh, site, "DMJ-" + N, 22_000, 20_000, "DMSRC-" + N);
        shift = shifts.start(sup, site);
        containers.openJerrican(sup, site, shift, "DMJ-" + N, Fixtures.seal("DMJ-" + N), "", SIG);
        shifts.start(supElsewhere, other);
    }

    @Test @Order(1)
    void aMarkingRegisteredByMistakeCanBeDeletedBeforeItsFirstDose() {
        TruckMarking t = truck("DEL1");
        assertEquals(1, markings.byShift(shift.id(), MarkingStatus.REGISTERED).stream().filter(x -> x.getUuid().equals(t.getUuid())).count());
        markings.delete(sup, site, shift, t.getUuid());
        assertEquals("DELETED", status(t));
        assertNotNull(Tx.call(c -> new org.jai.fuelmarking.dao.TruckMarkingDao().findByUuid(c, t.getUuid(), false)).orElseThrow().getDeletedTime(), "when it was deleted is kept");
        // it is on record but nowhere to be seen: not waiting to be dosed, not in any listing, and the shift does not wait for it
        assertEquals(0, markings.byShift(shift.id(), MarkingStatus.REGISTERED).stream().filter(x -> x.getUuid().equals(t.getUuid())).count());
        assertNull(reload(t), "listings leave deleted markings out");
        assertTrue(shifts.closeBlockers(shift).stream().noneMatch(b -> b.contains("not yet verified")), shifts.closeBlockers(shift).toString());
        // it is queued for head office like any other change
        String payload = Tx.call(c -> {
            try (var ps = c.prepareStatement("SELECT payload::text FROM sync_outbox WHERE entity_type = 'TRUCK_MARKING' AND entity_id = ? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, t.getUuid().toString());
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
            }
        });
        assertTrue(payload.contains("\"status\": \"DELETED\"") && payload.contains("deletedTime"), payload);
        // gone for good: it cannot be deleted again, dosed, or dispensed
        assertTrue(assertThrows(IllegalStateException.class, () -> markings.delete(sup, site, shift, t.getUuid())).getMessage().contains("already deleted"));
        assertTrue(assertThrows(IllegalStateException.class, () -> markings.dose(dosing, t.getUuid(), "C1", "DMJ-" + N, 60.0, "SCALE", SIG)).getMessage().contains("DELETED"));
        assertEquals(20_000, containers.find("DMJ-" + N).orElseThrow().currentVolumeMl(), 0.0, "no rum left the jerrican");
    }

    @Test @Order(2)
    void onlyTheSupervisorOfTheSiteOnAnOpenShiftCanDelete() {
        TruckMarking t = truck("DEL2");
        assertThrows(IllegalStateException.class, () -> markings.delete(dosing, site, shift, t.getUuid()), "dosing operators cannot");
        assertThrows(IllegalStateException.class, () -> markings.delete(wh, site, shift, t.getUuid()), "nor the warehouse manager");
        assertTrue(assertThrows(IllegalStateException.class, () -> markings.delete(sup, site, null, t.getUuid())).getMessage().contains("Start a shift first"));
        Shift otherShift = Tx.call(c -> new org.jai.fuelmarking.dao.ShiftDao().findOpen(c, other.id())).orElseThrow();
        assertTrue(assertThrows(IllegalStateException.class, () -> markings.delete(supElsewhere, other, otherShift, t.getUuid())).getMessage().contains("another site"));
        assertThrows(IllegalArgumentException.class, () -> markings.delete(sup, site, shift, UUID.randomUUID()), "unknown marking");
        assertEquals("REGISTERED", status(t), "every refusal left it as it was");
        markings.delete(sup, site, shift, t.getUuid());
    }

    @Test @Order(3)
    void onceDosingHasStartedItCannotBeDeleted() {
        TruckMarking t = truck("DEL3");
        // the first compartment is dosed: the rum has left the jerrican, so the marking stays
        assertEquals(60.0, markings.dose(dosing, t.getUuid(), "C1", "DMJ-" + N, 60.0, "SCALE", SIG), 0.0);
        String msg = assertThrows(IllegalStateException.class, () -> markings.delete(sup, site, shift, t.getUuid())).getMessage();
        assertTrue(msg.contains("already been dosed (1 of 2 compartments)") && msg.contains("cannot be deleted once dosing has started"), msg);
        assertEquals("REGISTERED", status(t));
        // ... and neither can it be when it is fully dosed, dispensed or verified
        markings.dose(dosing, t.getUuid(), "C2", "DMJ-" + N, 40.0, "SCALE", SIG);
        assertTrue(assertThrows(IllegalStateException.class, () -> markings.delete(sup, site, shift, t.getUuid())).getMessage().contains("already been dosed (2 of 2"));
        markings.dispense(dispense, t.getUuid(), SIG);
        assertThrows(IllegalStateException.class, () -> markings.delete(sup, site, shift, t.getUuid()));
        markings.verify(sup, t.getUuid(), SIG);
        assertThrows(IllegalStateException.class, () -> markings.delete(sup, site, shift, t.getUuid()));
        assertEquals("COMPLETED", status(t));
    }

    @Test @Order(4)
    void deletedMarkingsAreLeftOutOfReportsAndReconciliation() {
        TruckMarking keep = truck("DEL4");
        TruckMarking gone = truck("DEL5");
        markings.delete(sup, site, shift, gone.getUuid());
        var report = new ReportService().run(ReportService.Kind.MARKING_DETAIL, site.id(), LocalDateTime.now().toLocalDate(), LocalDateTime.now().toLocalDate());
        List<String> trucks = report.rows().stream().map(r -> r.get(2)).toList();
        assertTrue(trucks.contains(keep.getTruckNumber()) && !trucks.contains(gone.getTruckNumber()), trucks.toString());
        var byFuel = new ReportService().run(ReportService.Kind.MARKING_BY_FUEL, site.id(), LocalDateTime.now().toLocalDate(), LocalDateTime.now().toLocalDate());
        assertTrue(byFuel.rows().stream().mapToDouble(r -> Double.parseDouble(r.get(3))).sum() > 0);
        double before = byFuel.rows().stream().mapToDouble(r -> Double.parseDouble(r.get(3))).sum();
        markings.delete(sup, site, shift, keep.getUuid());
        var after = new ReportService().run(ReportService.Kind.MARKING_BY_FUEL, site.id(), LocalDateTime.now().toLocalDate(), LocalDateTime.now().toLocalDate());
        assertEquals(before - 10_000, after.rows().stream().mapToDouble(r -> Double.parseDouble(r.get(3))).sum(), 0.01, "its litres no longer count");
        assertEquals(0, shifts.reconcile(shift).varianceMl(), 0.05, "deleting a marking that took no rum changes nothing");
    }

    @Test @Order(5)
    void theShiftCanCloseWithDeletedMarkingsOnRecord() {
        containers.closeBack(sup, site, shift, "DMJ-" + N, "NEW-" + Fixtures.seal("DMJ-" + N), null, SIG);
        assertTrue(shifts.closeBlockers(shift).isEmpty(), shifts.closeBlockers(shift).toString());
        shifts.end(sup, shift, "done");
        assertTrue(shifts.findOpen(site.id()).isEmpty());
    }
}
