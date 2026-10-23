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

/** The weighed dose: tolerance, what leaves the jerrican, what is recorded, and reconciliation. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DosingIT {
    static final byte[] SIG = {1, 2, 3};
    static final String N = UUID.randomUUID().toString().substring(0, 6);
    static final Site site = new Site(UUID.randomUUID(), "DS" + N, "Dosing Site " + N, "FIXED");
    static final Omc omc = new Omc(UUID.randomUUID(), "DO" + N, "Dosing Oil " + N);
    static AppUser sup, dosing, wh, dispense;
    static final ShiftService shifts = new ShiftService();
    static final ContainerService containers = new ContainerService();
    static final MarkingService markings = new MarkingService();
    static Shift shift;

    static AppUser user(java.sql.Connection c, String name, Role r) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), name + N, name, r, site.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, u.username()); ps.setString(3, name); ps.setString(4, r.name()); ps.setObject(5, site.id());
            ps.executeUpdate();
        }
        return u;
    }

    static double num(String sql, Object... args) {
        return Tx.call(c -> {
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getDouble(1); }
            }
        });
    }

    static String text(String sql, Object... args) {
        return Tx.call(c -> {
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
            }
        });
    }

    @BeforeAll
    static void seed() {
        Tx.run(c -> {
            new MasterDataDao().upsertSite(c, site); new MasterDataDao().upsertOmc(c, omc);
            sup = user(c, "sup", Role.SUPERVISOR); dosing = user(c, "dose", Role.DOSING_OPERATOR); wh = user(c, "wh", Role.WAREHOUSE_MANAGER); dispense = user(c, "disp", Role.DISPENSE_OPERATOR);
            return null;
        });
        Fixtures.ibc(containers, wh, site, "DSRC-" + N, 900_000);
        Fixtures.plastic(containers, wh, site, "DJ-" + N, 1000, 1000, "DSRC-" + N);
        shift = shifts.start(sup, site);
        containers.openJerrican(sup, site, shift, "DJ-" + N, Fixtures.seal("DJ-" + N), "", SIG);
    }

    @Test
    void aTrailerOrWagonNumberIsOptionalAndRecordedUpperCase() {
        TruckMarking plain = truck("NOTRL", 1000);
        assertNull(plain.getTrailerNumber());
        TruckMarking pulled = markings.register(sup, site, shift, omc, "TRK" + N, " tl-" + N + " ", "DIESEL", 1000, "STANDARD", List.of(), SIG);
        assertEquals(("TL-" + N).toUpperCase(), pulled.getTrailerNumber());
        TruckMarking blank = markings.register(sup, site, shift, omc, "TRB" + N, "  ", "DIESEL", 1000, "STANDARD", List.of(), SIG);
        assertNull(blank.getTrailerNumber(), "blank means no trailer");
    }

    TruckMarking truck(String plate, double litres) {
        return markings.register(sup, site, shift, omc, plate + N, "DIESEL", litres, "STANDARD", List.of(), SIG);
    }

    double jerricanG() { return num("SELECT current_volume_ml FROM containers WHERE barcode = ?", "DJ-" + N); }

    @Test @Order(1)
    void aDoseOutsideTheToleranceIsRefusedAndNothingChanges() {
        TruckMarking t = truck("A1", 10_000);                                  // needs 100 g, accepted 98 - 102 g
        double before = jerricanG();
        String low = assertThrows(IllegalArgumentException.class, () -> markings.dose(dosing, t.getUuid(), "DJ-" + N, 97.0, "SCALE", SIG)).getMessage();
        assertTrue(low.contains("outside the allowed range") && low.contains("100.0 g") && low.contains("2.0 g") && low.contains("Add more rum"), low);
        String high = assertThrows(IllegalArgumentException.class, () -> markings.dose(dosing, t.getUuid(), "DJ-" + N, 103.0, "SCALE", SIG)).getMessage();
        assertTrue(high.contains("Too much rum"), high);
        assertEquals(before, jerricanG(), 1e-9, "a refused dose takes nothing from the jerrican");
        assertEquals("REGISTERED", text("SELECT status FROM truck_markings WHERE uuid = ?", t.getUuid()));
        assertEquals(0, num("SELECT count(*) FROM signatures WHERE entity_id = ? AND step = 'DOSING'", t.getUuid()));
    }

    @Test @Order(2)
    void theWeighedAmountNotTheCalculatedOneLeavesTheJerricanAndIsRecorded() {
        TruckMarking t = truck("A2", 10_000);
        double before = jerricanG();
        assertEquals(101.5, markings.dose(dosing, t.getUuid(), "DJ-" + N, 101.5, "MANUAL", SIG));
        assertEquals(before - 101.5, jerricanG(), 1e-9);
        assertEquals(101.5, num("SELECT dose_measured_g FROM truck_markings WHERE uuid = ?", t.getUuid()), 1e-9);
        assertEquals(100.0, num("SELECT dose_calculated_ml FROM truck_markings WHERE uuid = ?", t.getUuid()), 1e-9, "the requirement is kept alongside");
        assertEquals("MANUAL", text("SELECT dose_source FROM truck_markings WHERE uuid = ?", t.getUuid()));
        markings.dispense(dispense, t.getUuid(), SIG);
        assertEquals(101.5, num("SELECT rum_used FROM truck_markings WHERE uuid = ?", t.getUuid()), 1e-9, "the dispensed amount is the weighed one");
    }

    @Test @Order(3)
    void toleranceScalesWithTheTruckAndTheEdgesAreInclusive() {
        TruckMarking small = truck("A3", 5_000);                               // needs 50 g, accepted 49 - 51 g
        assertThrows(IllegalArgumentException.class, () -> markings.dose(dosing, small.getUuid(), "DJ-" + N, 48.9, "SCALE", SIG));
        assertEquals(51.0, markings.dose(dosing, small.getUuid(), "DJ-" + N, 51.0, "SCALE", SIG), "exactly at the limit is accepted");
        assertEquals("SCALE", text("SELECT dose_source FROM truck_markings WHERE uuid = ?", small.getUuid()));
        TruckMarking big = truck("A4", 20_000);                                // needs 200 g, accepted 196 - 204 g
        assertEquals(196.0, markings.dose(dosing, big.getUuid(), "DJ-" + N, 196.0, "SCALE", SIG));
    }

    @Test @Order(4)
    void badInputsAreRejectedBeforeAnythingIsTouched() {
        TruckMarking t = truck("A5", 10_000);
        double before = jerricanG();
        assertThrows(IllegalArgumentException.class, () -> markings.dose(dosing, t.getUuid(), "DJ-" + N, 0.0, "SCALE", SIG));
        assertThrows(IllegalArgumentException.class, () -> markings.dose(dosing, t.getUuid(), "DJ-" + N, -5.0, "SCALE", SIG));
        assertThrows(IllegalArgumentException.class, () -> markings.dose(dosing, t.getUuid(), "DJ-" + N, Double.NaN, "SCALE", SIG));
        assertThrows(IllegalArgumentException.class, () -> markings.dose(dosing, t.getUuid(), "DJ-" + N, 100.0, "GUESS", SIG));
        assertThrows(IllegalArgumentException.class, () -> markings.dose(dosing, t.getUuid(), "DJ-" + N, 100.0, "SCALE", null));
        assertThrows(IllegalStateException.class, () -> markings.dose(sup, t.getUuid(), "DJ-" + N, 100.0, "SCALE", SIG), "only the dosing operator doses");
        assertThrows(IllegalArgumentException.class, () -> markings.dose(dosing, t.getUuid(), "NOPE", 100.0, "SCALE", SIG));
        assertEquals(before, jerricanG(), 1e-9);
        assertEquals("REGISTERED", text("SELECT status FROM truck_markings WHERE uuid = ?", t.getUuid()));
    }

    @Test @Order(5)
    void aJerricanThatCannotCoverTheDoseIsRefused() {
        Fixtures.plastic(containers, wh, site, "DJ-LOW-" + N, 1000, 60, "DSRC-" + N);
        containers.openJerrican(sup, site, shift, "DJ-LOW-" + N, Fixtures.seal("DJ-LOW-" + N), "", SIG);
        TruckMarking t = truck("A6", 10_000);
        String msg = assertThrows(IllegalStateException.class, () -> markings.dose(dosing, t.getUuid(), "DJ-LOW-" + N, 100.0, "SCALE", SIG)).getMessage();
        assertTrue(msg.contains("holds 60.0 g") && msg.contains("need 100.0 g"), msg);
        assertEquals(60.0, num("SELECT current_volume_ml FROM containers WHERE barcode = ?", "DJ-LOW-" + N), 1e-9);
    }

    @Test @Order(6)
    void reconciliationAndReportsFollowWhatWasWeighedSoTheVarianceStaysZero() {
        Reconciliation r = shifts.reconcile(shift);
        double measured = num("SELECT COALESCE(SUM(dose_measured_g),0) FROM truck_markings WHERE shift_id = ?", shift.id());
        assertEquals(101.5 + 51.0 + 196.0, measured, 1e-9);
        assertEquals(measured, r.dispensedMl(), 1e-9, "reconciliation counts the weighed doses");
        assertEquals(0.0, r.varianceMl(), 1e-6, "what left the jerrican equals what was recorded");

        var detail = Tx.call(c -> new org.jai.fuelmarking.dao.ReportDao().markingDetail(c, site.id(), java.time.LocalDateTime.now().minusDays(1), java.time.LocalDateTime.now().plusDays(1)));
        int required = detail.headers().indexOf("Rum required (g)"), dosed = detail.headers().indexOf("Rum dosed (g)"), source = detail.headers().indexOf("Dose source");
        assertTrue(required >= 0 && dosed >= 0 && source >= 0, detail.headers().toString());
        var row = detail.rows().stream().filter(x -> x.get(detail.headers().indexOf("Truck")).startsWith("A2")).findFirst().orElseThrow();
        assertEquals("100.00", row.get(required));
        assertEquals("101.50", row.get(dosed));
        assertEquals("MANUAL", row.get(source));
    }

    @Test @Order(7)
    void refillingRemembersWhichBulkContainerTheJerricanCameFrom() {
        Fixtures.ibc(containers, wh, site, "DIBC-" + N, 500_000);
        Fixtures.register("DEMPTY-" + N, "JERRICAN_PLASTIC", 1000, "STANDARD");
        containers.receiveEmptyJerrican(wh, site, "DEMPTY-" + N, "STANDARD");
        containers.refill(wh, "DEMPTY-" + N, "DIBC-" + N, 874.3, "DE-" + N, null);
        assertEquals(874.3, num("SELECT current_volume_ml FROM containers WHERE barcode = ?", "DEMPTY-" + N), 1e-9);
        assertEquals(500_000 - 874.3, num("SELECT current_volume_ml FROM containers WHERE barcode = ?", "DIBC-" + N), 1e-9);
        assertEquals("DIBC-" + N, text("SELECT b.barcode FROM containers j JOIN containers b ON b.id = j.source_container_id WHERE j.barcode = ?", "DEMPTY-" + N));
        assertThrows(IllegalStateException.class, () -> containers.refill(wh, "DEMPTY-" + N, "DIBC-" + N, 500.0, "DE2-" + N, null), "a jerrican that is already filled and sealed cannot be refilled");
        Fixtures.register("DOVER-" + N, "JERRICAN_PLASTIC", 1000, "STANDARD");
        containers.receiveEmptyJerrican(wh, site, "DOVER-" + N, "STANDARD");
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.refill(wh, "DOVER-" + N, "DIBC-" + N, 1500.0, "DO-" + N, null)).getMessage().contains("Exceeds jerrican capacity"));
    }

    @Test @Order(8)
    void trucksRegisteredInTheSameSecondGetDistinctOrderNumbers() {
        java.util.Set<String> numbers = new java.util.HashSet<>();
        for (int i = 0; i < 6; i++) numbers.add(truck("S" + i, 8_000).getOrderNumber());
        assertEquals(6, numbers.size(), numbers.toString());
        assertTrue(numbers.stream().allMatch(n -> n.startsWith("DS" + N + "-")), numbers.toString());
    }
}
