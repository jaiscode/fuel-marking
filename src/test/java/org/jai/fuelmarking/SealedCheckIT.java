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

/** The reconciliation also counts the sealed jerricans and looks at each seal - against a real Postgres. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SealedCheckIT {
    static final byte[] SIG = {1, 2, 3};
    static final String N = Long.toString(System.nanoTime() % 1_000_000, 36);
    static final Site site = new Site(UUID.randomUUID(), "SK" + N, "Sealed Check Site " + N, "FIXED");
    static final Site other = new Site(UUID.randomUUID(), "SO" + N, "Other " + N, "FIXED");
    static final ContainerService containers = new ContainerService();
    static final ReconcileService reconcile = new ReconcileService();
    static AppUser sup, wh;
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

    static SealedCheck found(String b) { return new SealedCheck(b + "-" + N, "FOUND", null); }

    static Container get(String b) { return containers.find(b + "-" + N).orElseThrow(); }

    @BeforeAll
    static void seed() {
        Tx.run(c -> {
            new MasterDataDao().upsertSite(c, site); new MasterDataDao().upsertSite(c, other);
            sup = user(c, "sup", Role.SUPERVISOR); wh = user(c, "wh", Role.WAREHOUSE_MANAGER);
            return null;
        });
        Fixtures.ibc(containers, wh, site, "SKSRC-" + N, 900_000);
        for (String j : List.of("SKA", "SKB", "SKC", "SKD")) Fixtures.plastic(containers, wh, site, j + "-" + N, 22_000, 20_000, "SKSRC-" + N);
        shift = new ShiftService().start(sup, site);
        containers.openJerrican(sup, site, shift, "SKD-" + N, Fixtures.seal("SKD-" + N), "", SIG);       // one opened, three sealed
    }

    @Test @Order(1)
    void everySealedJerricanHasToBeChecked() {
        assertEquals(List.of("SKA-" + N, "SKB-" + N, "SKC-" + N), reconcile.sealedToCheck(site.id()).stream().map(Container::barcode).sorted().toList(), "only the sealed jerricans - not the opened one, not the IBC");
        var weighed = List.of(new StockEntry("SKD-" + N, 20_000, "MANUAL"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift, weighed, null, SIG)).getMessage().contains("Check every sealed jerrican"),
                "without the check of the sealed ones the reconciliation is refused");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift, weighed, List.of(found("SKA"), found("SKB")), null, SIG)).getMessage().contains("not checked: SKC-" + N));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift, weighed, List.of(found("SKA"), found("SKA"), found("SKB"), found("SKC")), null, SIG)).getMessage().contains("checked twice"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift, weighed, List.of(found("SKA"), found("SKB"), found("SKC"), found("SKD")), null, SIG)).getMessage().contains("is not a sealed jerrican"),
                "the opened one is weighed, not counted");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift, weighed, List.of(found("SKA"), found("SKB"), new SealedCheck("SKC-" + N, "LOST", "x")), null, SIG)).getMessage().contains("FOUND, MISSING or SEAL_BROKEN"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift, weighed, List.of(found("SKA"), found("SKB"), new SealedCheck("SKC-" + N, "MISSING", " ")), null, SIG)).getMessage().contains("say what you found"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reconcile.submit(sup, site, shift, weighed, List.of(found("SKA"), found("SKB"), new SealedCheck("SKC-" + N, "SEAL_BROKEN", null)), null, SIG)).getMessage().contains("what is wrong with the seal"));
        assertEquals(0, num("SELECT count(*) FROM stock_counts WHERE kind = 'SHIFT' AND site_id = ?", site.id()), 0.0, "every refusal left nothing behind");
    }

    @Test @Order(2)
    void whenEverythingIsFoundWithItsSealIntactItIsRecordedAndNothingChanges() {
        var weighed = List.of(new StockEntry("SKD-" + N, 20_000, "MANUAL"));
        StockCount sc = reconcile.submit(sup, site, shift, weighed, List.of(found("SKA"), found("SKB"), found("SKC")), null, SIG);
        assertEquals("SHIFT", sc.kind());
        assertEquals(80_000, sc.totalSystemG(), 0.0, "the opened jerrican and the three sealed ones");
        assertEquals(80_000, sc.totalCountedG(), 0.0);
        var lines = reconcile.lines(sc.id());
        assertEquals(4, lines.size());
        assertNull(lines.stream().filter(l -> l.barcode().equals("SKD-" + N)).findFirst().orElseThrow().checkResult(), "the weighed one has no seal check");
        assertEquals("FOUND", lines.stream().filter(l -> l.barcode().equals("SKA-" + N)).findFirst().orElseThrow().checkResult());
        assertEquals("SEALED", get("SKA").status());
        assertEquals(20_000, get("SKA").currentVolumeMl(), 0.0, "the sealed ones are never changed by looking at them");
        assertEquals(0, num("SELECT count(*) FROM incidents WHERE site_id = ? AND description LIKE 'Sealed jerrican%'", site.id()), 0.0);
    }

    @Test @Order(3)
    void aJerricanThatIsNotThereIsAnIncidentAndMarkedMissingAndABrokenSealIsFlagged() {
        var weighed = List.of(new StockEntry("SKD-" + N, 20_000, "MANUAL"));
        StockCount sc = reconcile.submit(sup, site, shift, weighed,
                List.of(found("SKA"), new SealedCheck("SKB-" + N, "MISSING", "not in the store room"), new SealedCheck("SKC-" + N, "SEAL_BROKEN", "seal 5555 is not the one on record")), "monthly", SIG);
        assertEquals(-20_000, sc.totalVarianceG(), 0.0, "the missing jerrican's rum is a difference");
        // missing: an incident for the rum it held, and it is marked missing - it can no longer be used
        Container b = get("SKB");
        assertEquals("MISSING", b.status());
        assertEquals(0, b.currentVolumeMl(), 0.0);
        assertEquals(1, num("SELECT count(*) FROM incidents WHERE container_id = (SELECT id FROM containers WHERE barcode = ?) AND incident_type = 'OTHER' AND quantity_ml = 20000 AND rum_type_code = 'STANDARD' AND description LIKE 'Sealed jerrican not found%not in the store room%'", "SKB-" + N), 0.0);
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.openJerrican(sup, site, shift, "SKB-" + N, Fixtures.seal("SKB-" + N), "", SIG)).getMessage().contains("MISSING"));
        assertTrue(assertThrows(IllegalStateException.class, () -> new TransferService().dispatch(wh, site, other, List.of("SKB-" + N), SIG)).getMessage().contains("only SEALED"));
        // a broken seal: recorded and flagged, the jerrican itself is left as it is
        assertEquals("SEALED", get("SKC").status());
        assertEquals(20_000, get("SKC").currentVolumeMl(), 0.0);
        var lines = reconcile.lines(sc.id());
        var c = lines.stream().filter(l -> l.barcode().equals("SKC-" + N)).findFirst().orElseThrow();
        assertEquals("SEAL_BROKEN", c.checkResult());
        assertEquals("seal 5555 is not the one on record", c.note());
        assertNull(c.incidentId());
        assertNotNull(lines.stream().filter(l -> l.barcode().equals("SKB-" + N)).findFirst().orElseThrow().incidentId());
        // it goes to head office with its result
        String payload = Tx.call(cn -> {
            try (var ps = cn.prepareStatement("SELECT payload::text FROM sync_outbox WHERE entity_type = 'STOCK_COUNT' AND entity_id = ?")) {
                ps.setString(1, sc.id().toString());
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
            }
        });
        assertTrue(payload.contains("\"check\": \"MISSING\"") && payload.contains("\"check\": \"SEAL_BROKEN\"") && payload.contains("\"check\": \"FOUND\""), payload);
        // the next count no longer lists the missing one
        assertEquals(List.of("SKA-" + N, "SKC-" + N), reconcile.sealedToCheck(site.id()).stream().map(Container::barcode).sorted().toList());
    }

    @Test @Order(4)
    void withNothingOpenOnlyTheSealedOnesAreChecked() {
        containers.declareEmpty(sup, site, shift, "SKD-" + N, "OTHER", "used up", SIG);
        assertTrue(reconcile.toReconcile(site.id()).isEmpty());
        StockCount sc = reconcile.submit(sup, site, shift, List.of(), List.of(found("SKA"), found("SKC")), null, SIG);
        assertEquals(40_000, sc.totalSystemG(), 0.0);
        assertEquals(0, sc.totalVarianceG(), 0.0);
    }

    @Test @Order(5)
    void withNoJerricanAtAllThereIsNothingToReconcile() {
        // the other site has no jerricans of its own
        AppUser sup2 = Tx.call(c -> user2(c));
        Shift s2 = new ShiftService().start(sup2, other);
        assertTrue(assertThrows(IllegalStateException.class, () -> reconcile.submit(sup2, other, s2, List.of(), null, SIG)).getMessage().contains("no opened jerrican"));
    }

    static AppUser user2(java.sql.Connection c) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), "sup2" + N, "sup2", Role.SUPERVISOR, other.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, u.username()); ps.setString(3, "sup2"); ps.setString(4, "SUPERVISOR"); ps.setObject(5, other.id());
            ps.executeUpdate();
        }
        return u;
    }
}
