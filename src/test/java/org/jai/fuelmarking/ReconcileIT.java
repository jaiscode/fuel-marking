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

/** The supervisor's reconciliation of the opened jerricans during a shift, against a real Postgres. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconcileIT {
    static final byte[] SIG = {1, 2, 3};
    static final String N = Long.toString(System.nanoTime() % 1_000_000, 36);
    static final Site site = new Site(UUID.randomUUID(), "RC" + N, "Reconcile Site " + N, "FIXED");
    static final ContainerService containers = new ContainerService();
    static final ReconcileService reconcile = new ReconcileService();
    static AppUser sup, wh, dosing;
    static Shift shift;
    static StockCount last;

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

    static StockEntry e(String barcode, double g, String cause, String note) { return new StockEntry(barcode, g, "MANUAL", cause, note); }

    @BeforeAll
    static void seed() {
        Tx.run(c -> {
            new MasterDataDao().upsertSite(c, site);
            sup = user(c, "sup", Role.SUPERVISOR); wh = user(c, "wh", Role.WAREHOUSE_MANAGER); dosing = user(c, "dose", Role.DOSING_OPERATOR);
            return null;
        });
        Fixtures.ibc(containers, wh, site, "RSRC-" + N, 900_000);
        Fixtures.plastic(containers, wh, site, "RA-" + N, 22_000, 20_000, "RSRC-" + N);
        Fixtures.plastic(containers, wh, site, "RB-" + N, 22_000, 10_000, "RSRC-" + N);
        shift = new ShiftService().start(sup, site);
    }

    @Test @Order(1)
    void nothingToReconcileUntilAJerricanIsOpened() {
        assertTrue(reconcile.toReconcile(site.id()).isEmpty());
        // nothing is open yet, but two jerricans are sealed: they have to be checked, so a reconciliation without that check is refused
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift, List.of(), null, SIG)).getMessage().contains("Check every sealed jerrican"));
        containers.openJerrican(sup, site, shift, "RA-" + N, Fixtures.seal("RA-" + N), "", SIG);
        containers.openJerrican(sup, site, shift, "RB-" + N, Fixtures.seal("RB-" + N), "", SIG);
        assertEquals(List.of("RA-" + N, "RB-" + N), reconcile.toReconcile(site.id()).stream().map(Container::barcode).sorted().toList(),
                "only opened jerricans - not the IBC");
    }

    @Test @Order(2)
    void refusedWithoutTheRightRoleSignatureOrAFullCount() {
        var both = List.of(e("RA-" + N, 20_000, null, null), e("RB-" + N, 10_000, null, null));
        assertThrows(IllegalStateException.class, () -> reconcile.submit(wh, site, shift, both, null, SIG), "supervisors only");
        assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift, both, null, null), "unsigned");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift, List.of(e("RA-" + N, 20_000, null, null)), null, SIG))
                .getMessage().contains("missing: RB-" + N));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift,
                List.of(e("RA-" + N, 1, null, null), e("RA-" + N, 1, null, null)), null, SIG)).getMessage().contains("twice"));
        assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift,
                List.of(e("RA-" + N, -5, null, null), e("RB-" + N, 10_000, null, null)), null, SIG));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift,
                List.of(e("RA-" + N, 20_000, null, null), e("RB-" + N, 10_000, null, null), e("RSRC-" + N, 1, null, null)), null, SIG)).getMessage().contains("not an opened jerrican"));
        Shift closed = new Shift(shift.id(), shift.siteId(), shift.supervisorId(), shift.startedAt(), null, "CLOSED", shift.openingRumMl(), null, null);
        assertTrue(assertThrows(IllegalStateException.class, () -> reconcile.submit(sup, site, closed, both, null, SIG)).getMessage().contains("No open shift"));
        assertTrue(assertThrows(IllegalStateException.class, () -> reconcile.submit(sup, site, null, both, null, SIG)).getMessage().contains("No open shift"));
    }

    @Test @Order(3)
    void aMatchingCountIsRecordedWithNoIncidentAndNoChange() {
        double incidentsBefore = num("SELECT count(*) FROM incidents WHERE site_id = ?", site.id());
        StockCount sc = reconcile.submit(sup, site, shift, List.of(e("RA-" + N, 20_000, null, null), e("RB-" + N, 10_000.4, null, null)), null, SIG);
        assertEquals("SHIFT", sc.kind());
        assertEquals(shift.id(), sc.shiftId());
        assertEquals(30_000, sc.totalSystemG(), 0.0);
        assertEquals(incidentsBefore, num("SELECT count(*) FROM incidents WHERE site_id = ?", site.id()), "within a gram is scale noise, not a loss");
        assertEquals(20_000, containers.find("RA-" + N).orElseThrow().currentVolumeMl(), 0.0);
        assertEquals(1, num("SELECT count(*) FROM signatures WHERE entity_type = 'STOCK_COUNT' AND entity_id = ?", sc.id()), 0.0);
        assertEquals(1, reconcile.history(site.id()).size());
        assertTrue(StockCountIT.outboxCount("STOCK_COUNT") >= 1);
    }

    @Test @Order(4)
    void aShortfallNeedsACauseAndBecomesAnIncidentThatCorrectsTheStock() {
        var short_ = List.of(e("RA-" + N, 19_400, null, null), e("RB-" + N, 10_000, null, null));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift, short_, null, SIG)).getMessage().contains("RA-" + N + " is short"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift,
                List.of(e("RA-" + N, 19_400, "BOGUS", null), e("RB-" + N, 10_000, null, null)), null, SIG)).getMessage().contains("is short"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift,
                List.of(e("RA-" + N, 19_400, "OTHER", "  "), e("RB-" + N, 10_000, null, null)), null, SIG)).getMessage().contains("describe what happened"));
        double before = num("SELECT count(*) FROM stock_counts WHERE kind = 'SHIFT' AND site_id = ?", site.id());
        assertEquals(before, num("SELECT count(*) FROM stock_counts WHERE kind = 'SHIFT' AND site_id = ?", site.id()), "a refused count leaves nothing behind");
        assertEquals(20_000, containers.find("RA-" + N).orElseThrow().currentVolumeMl(), 0.0);

        StockCount sc = reconcile.submit(sup, site, shift, List.of(e("RA-" + N, 19_400, "SPILLAGE", "dropped while decanting"), e("RB-" + N, 10_000, null, null)), "end of shift", SIG);
        assertEquals(-600, sc.totalVarianceG(), 0.0);
        assertEquals(19_400, containers.find("RA-" + N).orElseThrow().currentVolumeMl(), 0.0, "the system now holds what is physically there");
        assertEquals(1, num("SELECT count(*) FROM incidents WHERE site_id = ? AND container_id = (SELECT id FROM containers WHERE barcode = ?) AND incident_type = 'SPILLAGE' AND quantity_ml = 600 AND shift_id = ?",
                site.id(), "RA-" + N, shift.id()), 0.0);
        var lines = reconcile.lines(sc.id());
        var a = lines.stream().filter(l -> l.barcode().equals("RA-" + N)).findFirst().orElseThrow();
        assertEquals("SPILLAGE", a.cause());
        assertEquals("dropped while decanting", a.note());
        assertNotNull(a.incidentId());
        assertNull(lines.stream().filter(l -> l.barcode().equals("RB-" + N)).findFirst().orElseThrow().incidentId());
        // and the shift's own reconciliation agrees: the incident is counted as a loss, so nothing is unaccounted for
        assertEquals(0, new ShiftService().reconcile(shift).varianceMl(), 0.05);
    }

    @Test @Order(5)
    void otherNeedsADescriptionAndExtraRumNeedsANote() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift,
                List.of(e("RA-" + N, 19_400, null, null), e("RB-" + N, 12_000, null, null)), null, SIG)).getMessage().contains("holds more than the system says"));
        StockCount sc = last = reconcile.submit(sup, site, shift, List.of(e("RA-" + N, 19_000, "OTHER", "evaporation in the heat"), e("RB-" + N, 12_000, null, "topped up from an unrecorded drum")), null, SIG);
        assertEquals(1, num("SELECT count(*) FROM incidents WHERE site_id = ? AND incident_type = 'OTHER' AND description LIKE '%evaporation in the heat%'", site.id()), 0.0);
        assertEquals(19_000, containers.find("RA-" + N).orElseThrow().currentVolumeMl(), 0.0);
        assertEquals(10_000, containers.find("RB-" + N).orElseThrow().currentVolumeMl(), 0.0, "extra rum is noted, not added to the stock");
        var b = reconcile.lines(sc.id()).stream().filter(l -> l.barcode().equals("RB-" + N)).findFirst().orElseThrow();
        assertEquals(2_000, b.varianceG(), 0.0);
        assertNull(b.incidentId());
        assertEquals("topped up from an unrecorded drum", b.note());
    }

    @Test @Order(6)
    void theReconciliationIsQueuedForHeadOfficeWithItsCauses() {
        UUID id = last.id();
        String payload = Tx.call(c -> {
            try (var ps = c.prepareStatement("SELECT payload::text FROM sync_outbox WHERE entity_type = 'STOCK_COUNT' AND entity_id = ?")) {
                ps.setString(1, id.toString());
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
            }
        });
        assertTrue(payload.contains("\"kind\": \"SHIFT\"") && payload.contains("\"cause\": \"OTHER\"") && payload.contains("\"incidentId\""), payload);
    }

    @Test @Order(7)
    void incidentsCanBeOfTypeOtherButNeedADescription() {
        var incidents = new IncidentService();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> incidents.report(sup, site, shift, null, "STANDARD", "OTHER", 5, " ")).getMessage().contains("needs a description"));
        assertEquals("OTHER", incidents.report(sup, site, shift, null, "STANDARD", "OTHER", 5, "cracked cap").type());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> incidents.report(sup, site, shift, null, "STANDARD", "THEFT", 5, "x")).getMessage().contains("SPILLAGE, LEAKAGE or OTHER"));
    }

    @Test @Order(8)
    void theShiftCanThenBeClosedWithItsReconciliation() {
        var shifts = new ShiftService();
        assertTrue(assertThrows(IllegalStateException.class, () -> shifts.end(sup, shift, SIG, "done")).getMessage().contains("still open"), "opened jerricans have to be closed away first");
        containers.closeBack(sup, site, shift, "RA-" + N, "NEW-RA-" + N, null, SIG);
        containers.declareEmpty(sup, site, shift, "RB-" + N, "OTHER", "used up, a little left", SIG);
        var r = shifts.end(sup, shift, SIG, "done");
        assertEquals(0, r.varianceMl(), 0.05);
        assertEquals(0, shifts.unverifiedMarkings(shift));
    }
}
