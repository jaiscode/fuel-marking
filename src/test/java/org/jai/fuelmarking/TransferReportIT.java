package org.jai.fuelmarking;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.*;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.service.*;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Transfers + reports against a real Postgres; uses unique codes so it can be re-run on the same database. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransferReportIT {
    static final byte[] SIG = {1, 2, 3};
    static final String N = UUID.randomUUID().toString().substring(0, 6);
    static Site wh = new Site(UUID.randomUUID(), "WH" + N, "Warehouse " + N, "WAREHOUSE");
    static Site site = new Site(UUID.randomUUID(), "ST" + N, "Site " + N, "FIXED");
    static AppUser whMgr, sup, dosing;
    static Transfer transfer;

    static final TransferService transfers = new TransferService();
    static final ContainerService containers = new ContainerService();
    static final ReportService reports = new ReportService();

    static AppUser user(java.sql.Connection c, String name, Role r, Site s) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), name + N, name, r, s.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, u.username()); ps.setString(3, name); ps.setString(4, r.name()); ps.setObject(5, s.id());
            ps.executeUpdate();
        }
        return u;
    }

    @BeforeAll
    static void seed() {
        Tx.run(c -> {
            MasterDataDao m = new MasterDataDao();
            m.upsertSite(c, wh); m.upsertSite(c, site);
            whMgr = user(c, "whmgr", Role.WAREHOUSE_MANAGER, wh);
            sup = user(c, "sup", Role.SUPERVISOR, site);
            dosing = user(c, "dose", Role.DOSING_OPERATOR, site);
            return null;
        });
    }

    @Test @Order(1)
    void dispatchAndReceiveWithoutWeighing() {
        Fixtures.ibc(containers, whMgr, wh, "TSRC-" + N, 900_000);
        Fixtures.plastic(containers, whMgr, wh, "J1-" + N, 1000, 1000, "TSRC-" + N);
        Fixtures.plastic(containers, whMgr, wh, "J2-" + N, 1000, 1000, "TSRC-" + N);
        Fixtures.plastic(containers, whMgr, wh, "OPEN-" + N, 1000, 500, "TSRC-" + N);

        List<String> out = List.of("J1-" + N, "J2-" + N);
        assertThrows(IllegalStateException.class, () -> transfers.dispatch(sup, wh, site, out, SIG)); // wrong role
        assertThrows(IllegalArgumentException.class, () -> transfers.dispatch(whMgr, wh, wh, out, SIG)); // same site
        assertThrows(IllegalArgumentException.class, () -> transfers.dispatch(whMgr, wh, site, List.of(), SIG), "nothing to send");
        assertThrows(IllegalArgumentException.class, () -> transfers.dispatch(whMgr, wh, site, List.of("J1-" + N, "J1-" + N), SIG), "a container twice");
        assertThrows(IllegalArgumentException.class, () -> transfers.dispatch(whMgr, wh, site, List.of("J1-" + N, "NOPE-" + N), SIG), "unknown container");
        assertEquals(0, containers.inventory(wh.id()).stream().filter(k -> k.status().equals("IN_TRANSIT")).count(), "a refused dispatch moves nothing");
        transfer = transfers.dispatch(whMgr, wh, site, out, SIG);
        assertTrue(transfers.items(transfer.id()).stream().noneMatch(i -> i.received()), "nothing has arrived yet");

        assertEquals(2, containers.inventory(wh.id()).stream().filter(k -> k.status().equals("IN_TRANSIT")).count());
        assertEquals(1, transfers.incoming(site.id()).size());
        assertThrows(IllegalStateException.class, () -> transfers.dispatch(whMgr, wh, site, out, SIG)); // already in transit

        // partial receipt: J1 arrives first, J2 later
        assertTrue(assertThrows(IllegalStateException.class, () -> transfers.receive(sup, transfer, List.of("J1-" + N), SIG)).getMessage().contains("Start a shift first"),
                "a supervisor takes containers in during a shift only");
        new ShiftService().start(sup, site);
        assertThrows(IllegalArgumentException.class, () -> transfers.receive(sup, transfer, List.of(), SIG), "tick at least one");
        transfers.receive(sup, transfer, List.of("J1-" + N), SIG);
        assertEquals(1, transfers.incoming(site.id()).size()); // still outstanding
        assertEquals(List.of(true, false), transfers.items(transfer.id()).stream().map(TransferItem::received).toList());
        assertThrows(IllegalStateException.class, () -> transfers.receive(sup, transfer, List.of("J1-" + N), SIG), "J1 was already received");
        transfers.receive(sup, transfer, List.of("J2-" + N), SIG);
        assertTrue(transfers.incoming(site.id()).isEmpty());
        assertEquals(2, containers.inventory(site.id()).stream().filter(k -> k.status().equals("SEALED")).count());
    }

    @Test @Order(2)
    void reportsReflectActivity() {
        LocalDate today = LocalDate.now();
        ReportTable inv = reports.run(ReportService.Kind.INVENTORY, site.id(), today, today);
        assertEquals(List.of("Container type", "Status", "Containers", "Rum (kg)", "Capacity (kg)"), inv.headers());
        assertEquals("JERRICAN_PLASTIC", inv.rows().get(0).get(0));
        assertEquals("2", inv.rows().get(0).get(2));

        ReportTable tr = reports.run(ReportService.Kind.TRANSFERS, site.id(), today, today);
        assertEquals(2, tr.rows().size());
        assertEquals(List.of("Dispatched", "From", "To", "Status", "Container", "Type", "Received"), tr.headers());
        assertTrue(tr.rows().stream().allMatch(r -> !r.get(6).isBlank()), "both containers have a receipt time");

        ReportTable fuel = reports.run(ReportService.Kind.MARKING_BY_FUEL, site.id(), today, today);
        assertTrue(fuel.rows().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> reports.run(ReportService.Kind.INCIDENTS, site.id(), today, today.minusDays(1)));
    }

    @Test @Order(3)
    void everyReportRunsAgainstTheWorkflowData() {
        // WorkflowIT's site holds registered/dosed/verified trucks and an incident when run first
        UUID siteId = Tx.call(c -> {
            try (var rs = c.createStatement().executeQuery("SELECT site_id FROM truck_markings LIMIT 1")) {
                return rs.next() ? rs.getObject(1, UUID.class) : site.id();
            }
        });
        LocalDate today = LocalDate.now();
        for (ReportService.Kind k : ReportService.Kind.values()) {
            ReportTable t = reports.run(k, siteId, today, today);
            assertFalse(t.headers().isEmpty(), k.toString());
            t.rows().forEach(r -> assertEquals(t.headers().size(), r.size()));
        }
    }

    @Test @Order(4)
    void csvExportQuotesAndNeutralisesFormulas() throws Exception {
        ReportTable t = new ReportTable(List.of("A", "B"), List.of(List.of("=1+1", "say \"hi\", ok"), List.of("-3.5", "x")));
        Path f = Files.createTempFile("report", ".csv");
        reports.exportCsv(t, f);
        byte[] raw = Files.readAllBytes(f);
        assertEquals(0xEF, raw[0] & 0xFF); assertEquals(0xBB, raw[1] & 0xFF); assertEquals(0xBF, raw[2] & 0xFF);   // BOM: Excel reads UTF-8 correctly
        List<String> lines = Files.readAllLines(f);
        lines.set(0, lines.get(0).replace("\uFEFF", ""));
        assertEquals("\"A\",\"B\"", lines.get(0));
        assertEquals("\"'=1+1\",\"say \"\"hi\"\", ok\"", lines.get(1));
        assertEquals("\"-3.5\",\"x\"", lines.get(2));
        Files.delete(f);
    }

    @Test @Order(5)
    void accentsAndNonLatinNamesSurviveTheCsvExport() throws Exception {
        ReportTable t = new ReportTable(List.of("Site", "Company"), List.of(List.of("Zürich Dépôt", "Нефть Ltd \u2013 مصر")));
        Path f = Files.createTempFile("unicode", ".csv");
        reports.exportCsv(t, f);
        String text = new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\uFEFF"), "starts with the byte-order mark Excel needs");
        assertTrue(text.contains("Zürich Dépôt") && text.contains("Нефть Ltd \u2013 مصر"), text);
        Files.delete(f);
    }

}
