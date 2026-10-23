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

/** The warehouse manager's stock reconciliation, against a real Postgres. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StockCountIT {
    static final byte[] SIG = {1, 2, 3};
    static final String N = Long.toString(System.nanoTime() % 1_000_000, 36);
    static final Site site = new Site(UUID.randomUUID(), "SC" + N, "Count Warehouse " + N, "WAREHOUSE");
    static final Site other = new Site(UUID.randomUUID(), "SO" + N, "Other " + N, "FIXED");
    static final ContainerService containers = new ContainerService();
    static final StockCountService counts = new StockCountService();
    static AppUser wh, sup;

    static AppUser user(java.sql.Connection c, String name, Role r) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), name + N, name, r, site.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, u.username()); ps.setString(3, name); ps.setString(4, r.name()); ps.setObject(5, site.id());
            ps.executeUpdate();
        }
        return u;
    }

    static long outboxCount(String type) {
        return Tx.call(c -> {
            try (var ps = c.prepareStatement("SELECT count(*) FROM sync_outbox WHERE entity_type = ?")) {
                ps.setString(1, type);
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
            }
        });
    }

    @BeforeAll
    static void seed() {
        Tx.run(c -> {
            new MasterDataDao().upsertSite(c, site); new MasterDataDao().upsertSite(c, other);
            wh = user(c, "wh", Role.WAREHOUSE_MANAGER); sup = user(c, "sup", Role.SUPERVISOR);
            return null;
        });
    }

    @Test @Order(1)
    void nothingToCountWithoutABulkContainer() {
        assertTrue(counts.toCount(site.id()).isEmpty());
        assertTrue(assertThrows(IllegalStateException.class, () -> counts.submit(wh, site, List.of(), null, SIG)).getMessage().contains("no IBC or drum"));
    }

    @Test @Order(2)
    void everyIbcAndDrumIsCountedAndTheDifferencesAreRecordedWithoutChangingStock() {
        Fixtures.ibc(containers, wh, site, "CA-" + N, 600_000);
        containers.receiveBulk(wh, site, "CB-" + N, "DRUM", 200_000, 150_000, "STANDARD", null, null);
        // a jerrican is not counted here, and a container on the road is somebody else's to count
        Fixtures.plastic(containers, wh, site, "CJ-" + N, 22_000, 20_000, "CA-" + N);
        Fixtures.ibc(containers, wh, site, "CT-" + N, 100_000);
        new TransferService().dispatch(wh, site, other, List.of("CT-" + N), SIG);
        assertEquals(List.of("CA-" + N, "CB-" + N), counts.toCount(site.id()).stream().map(Container::barcode).sorted().toList(),
                "only the IBC and the drum that are here (CA is now 580 kg after refilling CJ)");
        double systemA = containers.find("CA-" + N).orElseThrow().currentVolumeMl();      // 580 000 g after the jerrican was filled
        assertEquals(580_000, systemA, 0.0);

        // refused: wrong role, missing container, unknown container, twice, negative, bad source, no signature
        assertThrows(IllegalStateException.class, () -> counts.submit(sup, site, List.of(), null, SIG));
        assertThrows(IllegalArgumentException.class, () -> counts.submit(wh, site, List.of(new StockEntry("CA-" + N, 1, "MANUAL"), new StockEntry("CB-" + N, 1, "MANUAL")), null, null), "unsigned");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> counts.submit(wh, site, List.of(new StockEntry("CA-" + N, 580_000, "SCALE")), null, SIG)).getMessage().contains("missing: CB-" + N));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> counts.submit(wh, site, List.of(new StockEntry("CA-" + N, 1, "MANUAL"), new StockEntry("CB-" + N, 1, "MANUAL"),
                new StockEntry("CT-" + N, 1, "MANUAL")), null, SIG)).getMessage().contains("not an IBC or drum held at"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> counts.submit(wh, site, List.of(new StockEntry("CA-" + N, 1, "MANUAL"), new StockEntry("CA-" + N, 1, "MANUAL")), null, SIG)).getMessage().contains("twice"));
        assertThrows(IllegalArgumentException.class, () -> counts.submit(wh, site, List.of(new StockEntry("CA-" + N, -1, "MANUAL"), new StockEntry("CB-" + N, 1, "MANUAL")), null, SIG));
        assertThrows(IllegalArgumentException.class, () -> counts.submit(wh, site, List.of(new StockEntry("CA-" + N, 1, "GUESS"), new StockEntry("CB-" + N, 1, "MANUAL")), null, SIG));
        assertThrows(IllegalArgumentException.class, () -> counts.submit(wh, site, List.of(new StockEntry("CA-" + N, 1, "MANUAL"), new StockEntry("CB-" + N, 1, "MANUAL")), "x".repeat(501), SIG));
        assertTrue(counts.history(site.id()).isEmpty(), "refused counts leave nothing behind");
        long queuedBefore = outboxCount("STOCK_COUNT");   // other suites share this database: compare, do not count from zero

        // the IBC is 0.5 kg short, the drum is exactly right
        StockCount sc = counts.submit(wh, site, List.of(new StockEntry("CA-" + N, 579_500, "SCALE"), new StockEntry("CB-" + N, 150_000, "MANUAL")), "  month end  ", SIG);
        assertEquals(730_000, sc.totalSystemG(), 0.0);
        assertEquals(729_500, sc.totalCountedG(), 0.0);
        assertEquals(-500, sc.totalVarianceG(), 0.0);
        assertEquals("month end", sc.comment());
        List<StockCountLine> lines = counts.lines(sc.id());
        assertEquals(List.of("CA-" + N, "CB-" + N), lines.stream().map(StockCountLine::barcode).toList());
        assertEquals(-500, lines.get(0).varianceG(), 0.0);
        assertEquals("SCALE", lines.get(0).source());
        assertEquals("IBC", lines.get(0).containerType());
        assertEquals("STANDARD", lines.get(0).rumTypeCode());
        assertEquals(0, lines.get(1).varianceG(), 0.0);
        assertEquals("MANUAL", lines.get(1).source());

        // the count is a record: stock is as it was, and it is signed and on its way to head office
        assertEquals(580_000, containers.find("CA-" + N).orElseThrow().currentVolumeMl(), 0.0);
        assertEquals(1, counts.history(site.id()).size());
        assertEquals(1, Tx.<Integer>call(c -> {
            try (var ps = c.prepareStatement("SELECT count(*) FROM signatures WHERE entity_type = 'STOCK_COUNT' AND entity_id = ?")) {
                ps.setObject(1, sc.id());
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
            }
        }));
        assertEquals(queuedBefore + 1, outboxCount("STOCK_COUNT"));
        String payload = Tx.call(c -> {
            try (var ps = c.prepareStatement("SELECT payload::text FROM sync_outbox WHERE entity_type = 'STOCK_COUNT' AND entity_id = ?")) {
                ps.setString(1, sc.id().toString());
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
            }
        });
        assertTrue(payload.contains("\"totalVarianceG\": -500") && payload.contains("\"countedG\": 579500") && payload.contains("\"varianceG\": -500"), payload);

        // counts can be repeated, and the report lists them
        counts.submit(wh, site, List.of(new StockEntry("CA-" + N, 580_000, "MANUAL"), new StockEntry("CB-" + N, 150_000, "MANUAL")), null, SIG);
        assertEquals(2, counts.history(site.id()).size());
        var report = new ReportService().run(ReportService.Kind.STOCK_COUNTS, site.id(), java.time.LocalDate.now(), java.time.LocalDate.now());
        assertEquals(List.of("Counted", "Counted by", "Container", "Type", "Rum type", "System (kg)", "Counted (kg)", "Difference (kg)", "Source", "Comment"), report.headers());
        assertEquals(4, report.rows().size());
        assertTrue(report.rows().stream().anyMatch(r -> r.get(2).equals("CA-" + N) && r.get(7).equals("-0.50")), report.rows().toString());
    }
}
