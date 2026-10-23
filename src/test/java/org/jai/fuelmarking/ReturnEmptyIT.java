package org.jai.fuelmarking;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;
import org.jai.fuelmarking.service.*;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A finished jerrican is declared empty at the marking site, sent back to the warehouse and refilled - against a real Postgres. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReturnEmptyIT {
    static final byte[] SIG = {1, 2, 3};
    static final String N = Long.toString(System.nanoTime() % 1_000_000, 36);
    static final Site wh = new Site(UUID.randomUUID(), "RW" + N, "Return Warehouse " + N, "WAREHOUSE");
    static final Site ms = new Site(UUID.randomUUID(), "RM" + N, "Return Marking " + N, "FIXED");
    static final Site ms2 = new Site(UUID.randomUUID(), "R2" + N, "Return Marking Two " + N, "FIXED");
    static final ContainerService containers = new ContainerService();
    static final TransferService transfers = new TransferService();
    static AppUser manager, sup, supElsewhere;
    static Shift shift;

    static AppUser user(java.sql.Connection c, Site s, String name, Role r) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), name + N, name, r, s.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, u.username()); ps.setString(3, name); ps.setString(4, r.name()); ps.setObject(5, s.id());
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

    static Container get(String barcode) { return containers.find(barcode).orElseThrow(); }

    @BeforeAll
    static void seed() {
        Tx.run(c -> {
            var m = new MasterDataDao();
            m.upsertSite(c, wh); m.upsertSite(c, ms); m.upsertSite(c, ms2);
            manager = user(c, wh, "mgr", Role.WAREHOUSE_MANAGER); sup = user(c, ms, "sup", Role.SUPERVISOR); supElsewhere = user(c, ms2, "sup2", Role.SUPERVISOR);
            return null;
        });
        Fixtures.ibc(containers, manager, wh, "RTSRC-" + N, 900_000);
        for (String j : List.of("JA", "JB", "JC"))
            Fixtures.plastic(containers, manager, wh, j + "-" + N, 22_000, 20_000, "RTSRC-" + N);
        transfers.dispatch(manager, wh, ms, List.of("JA-" + N, "JB-" + N, "JC-" + N), SIG);
        shift = new ShiftService().start(sup, ms);                         // a supervisor takes containers in during a shift only
        new ShiftService().start(supElsewhere, ms2);
        for (Transfer t : transfers.incoming(ms.id()))
            transfers.receive(sup, t, transfers.items(t.id()).stream().map(TransferItem::barcode).toList(), SIG);
        for (String j : List.of("JA", "JB"))
            containers.openJerrican(sup, ms, shift, j + "-" + N, Fixtures.seal(j + "-" + N), "", SIG);
    }

    @Test @Order(1)
    void onlyAnOpenedJerricanCanBeDeclaredEmptyBySupervisorsOfThatSiteOnAnOpenShift() {
        assertThrows(IllegalStateException.class, () -> containers.declareEmpty(manager, ms, shift, "JA-" + N, "SPILLAGE", null, SIG), "supervisors only");
        assertThrows(IllegalArgumentException.class, () -> containers.declareEmpty(sup, ms, shift, "JA-" + N, "SPILLAGE", null, null), "unsigned");
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.declareEmpty(sup, ms, null, "JA-" + N, "SPILLAGE", null, SIG)).getMessage().contains("Start a shift"));
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.declareEmpty(sup, ms, shift, "JC-" + N, "SPILLAGE", null, SIG)).getMessage().contains("only an opened jerrican"), "JC is still sealed");
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.declareEmpty(supElsewhere, ms2, shift, "JA-" + N, "SPILLAGE", null, SIG)).getMessage().contains("another site"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.declareEmpty(sup, ms, shift, "RTSRC-" + N, null, null, SIG)).getMessage().contains("Not a jerrican"));
        assertThrows(IllegalArgumentException.class, () -> containers.declareEmpty(sup, ms, shift, "NOPE-" + N, null, null, SIG));
        assertEquals("OPENED", get("JA-" + N).status(), "every refusal left it as it was");
    }

    @Test @Order(2)
    void rumStillInTheJerricanMustBeAccountedFor() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.declareEmpty(sup, ms, shift, "JA-" + N, null, null, SIG)).getMessage().contains("still shown in JA-" + N));
        assertThrows(IllegalArgumentException.class, () -> containers.declareEmpty(sup, ms, shift, "JA-" + N, "THEFT", null, SIG));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.declareEmpty(sup, ms, shift, "JA-" + N, "OTHER", " ", SIG)).getMessage().contains("Describe what happened"));
        assertEquals(20_000, get("JA-" + N).currentVolumeMl(), 0.0, "nothing changed while it was refused");

        Container done = containers.declareEmpty(sup, ms, shift, "JA-" + N, "OTHER", "residue stuck to the walls", SIG);
        assertEquals("EMPTY", done.status());
        assertEquals(0, done.currentVolumeMl(), 0.0);
        assertNull(done.topSeal(), "an emptied jerrican has no seals: they are discarded");
        assertNull(done.bottomSeal());
        assertEquals(1, num("SELECT count(*) FROM discarded_seals WHERE seal = ?", Fixtures.seal("JA-" + N)), 0.0, "the number is remembered as used");
        assertEquals(1, num("SELECT count(*) FROM incidents WHERE site_id = ? AND incident_type = 'OTHER' AND quantity_ml = 20000 AND description LIKE '%residue stuck%'", ms.id()), 0.0);
        assertEquals(1, num("SELECT count(*) FROM signatures WHERE step = ?", "EMPTY:JA-" + N), 0.0);
        assertEquals(0, new ShiftService().reconcile(shift).varianceMl(), 0.05, "the shift still adds up: the rum left is an incident loss");
    }

    @Test @Order(3)
    void aJerricanThatIsUsedUpNeedsNoExplanation() {
        Tx.run(c -> { try (var ps = c.prepareStatement("UPDATE containers SET current_volume_ml = 0.4 WHERE barcode = ?")) { ps.setString(1, "JB-" + N); ps.executeUpdate(); } return null; });   // a fraction of a gram is scale noise
        Container done = containers.declareEmpty(sup, ms, shift, "JB-" + N, null, null, SIG);
        assertEquals("EMPTY", done.status());
        assertNull(done.topSeal(), "its seal is discarded too");
        assertEquals(0, num("SELECT count(*) FROM incidents WHERE site_id = ? AND quantity_ml < 1", ms.id()), 0.0);
    }

    @Test @Order(4)
    void onlyEmptyJerricansGoBackAndOnlyToAWarehouse() {
        assertThrows(IllegalStateException.class, () -> transfers.returnEmpties(manager, wh, ms, List.of("JA-" + N), SIG), "supervisors send empties back");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> transfers.returnEmpties(sup, ms, ms2, List.of("JA-" + N), SIG)).getMessage().contains("not one"), "not to another marking site");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> transfers.returnEmpties(sup, ms, ms, List.of("JA-" + N), SIG)).getMessage().contains("different"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> transfers.returnEmpties(sup, ms, wh, List.of(), SIG)).getMessage().contains("at least one"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> transfers.returnEmpties(sup, ms, wh, List.of("JA-" + N, "JA-" + N), SIG)).getMessage().contains("twice"));
        assertTrue(assertThrows(IllegalStateException.class, () -> transfers.returnEmpties(sup, ms, wh, List.of("JA-" + N, "JC-" + N), SIG)).getMessage().contains("declare it empty"), "JC is sealed");
        assertTrue(assertThrows(IllegalStateException.class, () -> transfers.returnEmpties(supElsewhere, ms2, wh, List.of("JA-" + N), SIG)).getMessage().contains("not at"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> transfers.returnEmpties(sup, ms, wh, List.of("RTSRC-" + N), SIG)).getMessage().contains("only jerricans"));
        assertEquals("EMPTY", get("JA-" + N).status(), "a refused dispatch moved nothing");
        assertTrue(transfers.incoming(wh.id()).isEmpty());
    }

    @Test @Order(5)
    void theEmptiesTravelBackAndArriveEmptyAtTheWarehouse() {
        Transfer t = transfers.returnEmpties(sup, ms, wh, List.of("JA-" + N, "JB-" + N));      // no signature is asked for
        assertEquals("RETURN", t.kind());
        assertEquals(0, num("SELECT count(*) FROM signatures WHERE entity_type = 'TRANSFER' AND entity_id = ?", t.id()), 0.0);
        assertEquals("IN_TRANSIT", get("JA-" + N).status());
        assertEquals(List.of(t.id()), transfers.incoming(wh.id()).stream().map(Transfer::id).toList());
        assertEquals("RETURN", transfers.incoming(wh.id()).get(0).kind());

        // this site's copy of the record can be stale (a pulled transfer does not bring the container's current state): receiving still makes it empty and unsealed
        Tx.run(c -> { try (var ps = c.prepareStatement("UPDATE containers SET status = 'IN_TRANSIT', current_volume_ml = 18000, top_seal = 'STALE1', bottom_seal = 'STALE2' WHERE barcode = ?")) { ps.setString(1, "JB-" + N); ps.executeUpdate(); } return null; });
        assertTrue(assertThrows(IllegalStateException.class, () -> transfers.receive(sup, t, List.of("JA-" + N))).getMessage().contains("warehouse manager"));
        transfers.receive(manager, t, List.of("JA-" + N));
        Container a = get("JA-" + N);
        assertEquals("EMPTY", a.status(), "arrives empty, not sealed");
        assertNull(a.topSeal(), "and without seals");
        assertEquals(wh.id(), a.siteId());
        assertEquals("IN_TRANSIT", get("JB-" + N).status(), "the rest is still on the road");
        transfers.receive(manager, t, List.of("JB-" + N));
        Container b = get("JB-" + N);
        assertEquals("EMPTY", b.status(), "a returned jerrican is empty, whatever the local copy said");
        assertEquals(0, b.currentVolumeMl(), 0.0);
        assertNull(b.topSeal());
        assertNull(b.bottomSeal());
        assertEquals(1, num("SELECT count(*) FROM discarded_seals WHERE seal = 'STALE1'"), 0.0, "the old seal numbers are remembered");
        assertEquals(0, num("SELECT count(*) FROM signatures WHERE entity_type = 'TRANSFER' AND entity_id = ?", t.id()), 0.0, "sending back and receiving need no signature");
        assertTrue(transfers.incoming(wh.id()).isEmpty(), "all received");

        String payload = Tx.call(c -> {
            try (var ps = c.prepareStatement("SELECT payload::text FROM sync_outbox WHERE entity_type = 'TRANSFER' AND entity_id = ?")) {
                ps.setString(1, t.id().toString());
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
            }
        });
        assertTrue(payload.contains("\"kind\": \"RETURN\""), payload);
    }

    @Test @Order(6)
    void theReturnedJerricanCanBeRefilledWithNewSealsOnly() {
        // it came back with no seals; the one that was discarded cannot go on again - a new seal is fitted when it is refilled
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.refill(manager, "JA-" + N, "RTSRC-" + N, 20_000, Fixtures.seal("JA-" + N), null))
                .getMessage().contains("used before") );
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.refill(manager, "JB-" + N, "RTSRC-" + N, 10_000, Fixtures.seal("JA-" + N), null))
                .getMessage().contains("used before"), "nor on another jerrican");
        containers.refill(manager, "JA-" + N, "RTSRC-" + N, 20_000, "NEW-" + Fixtures.seal("JA-" + N), null);
        Container a = get("JA-" + N);
        assertEquals("SEALED", a.status());
        assertEquals(20_000, a.currentVolumeMl(), 0.0);
        assertEquals("NEW-" + Fixtures.seal("JA-" + N), a.topSeal());
        // and a supply transfer still arrives sealed
        Transfer out = transfers.dispatch(manager, wh, ms, List.of("JA-" + N), SIG);
        assertEquals("SUPPLY", out.kind());
        transfers.receive(sup, out, List.of("JA-" + N), SIG);
        assertEquals("SEALED", get("JA-" + N).status());
        assertEquals(ms.id(), get("JA-" + N).siteId());
    }

    @Test @Order(7)
    void aTransferPulledFromHeadOfficeKeepsItsKind() throws Exception {
        UUID id = UUID.randomUUID();
        String json = "{\"transfer\":{\"id\":\"" + id + "\",\"fromSiteId\":\"" + ms.id() + "\",\"toSiteId\":\"" + wh.id() + "\",\"status\":\"DISPATCHED\",\"dispatchedAt\":\"" + LocalDateTime.now().withNano(0) + "\",\"kind\":\"RETURN\"},"
                + "\"items\":[{\"barcode\":\"JC-" + N + "\",\"received\":false}]}";
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        Tx.run(c -> { new org.jai.fuelmarking.sync.InboundApplier().transfer(c, node); return null; });
        assertEquals("RETURN", transfers.incoming(wh.id()).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow().kind(), "pulled returns are not mistaken for supplies");
        UUID older = UUID.randomUUID();
        String legacy = json.replace(id.toString(), older.toString()).replace(",\"kind\":\"RETURN\"", "");
        var legacyNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(legacy);
        Tx.run(c -> { new org.jai.fuelmarking.sync.InboundApplier().transfer(c, legacyNode); return null; });
        assertEquals("SUPPLY", transfers.incoming(wh.id()).stream().filter(x -> x.id().equals(older)).findFirst().orElseThrow().kind(), "a transfer without a kind is a supply");
    }

    @Test @Order(8)
    void aSiteTakesThePulledStateOfAContainerItDoesNotHoldButNeverOverwritesOneItDoes() throws Exception {
        Container jc = get("JC-" + N);                                  // sealed at the marking site, 20 kg
        String payload = "{\"id\":\"" + jc.id() + "\",\"barcode\":\"JC-" + N + "\",\"type\":\"" + jc.type().code() + "\",\"capacityMl\":22000,\"currentVolumeMl\":15000,"
                + "\"topSeal\":\"PULLED1\",\"bottomSeal\":null,\"status\":\"SEALED\",\"siteId\":\"" + ms.id() + "\",\"rumTypeCode\":\"STANDARD\"}";
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        try {
            // this installation is the OTHER site: the container is not in its custody, so what head office says it is (its seals, its rum) is taken
            System.setProperty("sync.site-id", ms2.id().toString());
            Tx.run(c -> { new org.jai.fuelmarking.sync.InboundApplier().container(c, node); return null; });
            assertEquals(15_000, get("JC-" + N).currentVolumeMl(), 0.0);
            assertEquals("PULLED1", get("JC-" + N).topSeal());
            // this installation is the site that holds it: its own copy is authoritative and is not overwritten
            System.setProperty("sync.site-id", ms.id().toString());
            var again = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload.replace("15000", "9999").replace("PULLED1", "PULLED2"));
            Tx.run(c -> { new org.jai.fuelmarking.sync.InboundApplier().container(c, again); return null; });
            assertEquals(15_000, get("JC-" + N).currentVolumeMl(), 0.0, "a container in our custody keeps its local state");
            assertEquals("PULLED1", get("JC-" + N).topSeal());
        } finally {
            System.clearProperty("sync.site-id");
        }
    }
}