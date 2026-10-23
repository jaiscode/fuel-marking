package org.jai.fuelmarking;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;
import org.jai.fuelmarking.service.ShiftService;
import org.jai.fuelmarking.sync.MasterDataApplier;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A mobile site must say where it is working before a shift starts; a fixed site need not. Locations arrive as master data. */
class MobileSiteIT {
    static final String N = Long.toString(System.nanoTime() % 1_000_000, 36);
    static final Site mobile = new Site(UUID.randomUUID(), "MB" + N, "Mobile " + N, "MOBILE");
    static final Site fixed = new Site(UUID.randomUUID(), "FX" + N, "Fixed " + N, "FIXED");
    static final UUID locId = UUID.randomUUID(), retiredId = UUID.randomUUID();
    static AppUser mobSup, fixSup;
    final ShiftService shifts = new ShiftService();

    static AppUser user(java.sql.Connection c, Site s, String name) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), name + N, name, Role.SUPERVISOR, s.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, u.username()); ps.setString(3, name); ps.setString(4, u.role().name()); ps.setObject(5, s.id());
            ps.executeUpdate();
        }
        return u;
    }

    static String message(UUID id, String code, String name, boolean active) {
        return "{\"type\":\"LOCATION\",\"op\":\"UPSERT\",\"payload\":{\"id\":\"" + id + "\",\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"active\":" + active + "}}";
    }

    @BeforeAll
    static void seed() {
        Tx.run(c -> {
            var m = new MasterDataDao();
            m.upsertSite(c, mobile); m.upsertSite(c, fixed);
            mobSup = user(c, mobile, "mobsup"); fixSup = user(c, fixed, "fixsup");
            var a = new MasterDataApplier();
            a.apply(c, message(locId, "LC" + N, "Kisumu border " + N, true));
            a.apply(c, message(retiredId, "RT" + N, "Old camp " + N, false));
            return null;
        });
    }

    @Test
    void aMobileSiteCannotStartAShiftWithoutALocation() {
        assertThrows(IllegalArgumentException.class, () -> shifts.start(mobSup, mobile));
        assertTrue(Tx.<Boolean>call(c -> new org.jai.fuelmarking.dao.ShiftDao().findOpen(c, mobile.id()).isEmpty()));
    }

    @Test
    void aRetiredLocationCannotBeChosen() {
        assertThrows(IllegalArgumentException.class, () -> shifts.start(mobSup, mobile, new Location(retiredId, "RT" + N, "Old camp " + N)));
    }

    @Test
    void locationsReplicatedAsMasterDataAreOfferedAndRetiredOnesAreNot() {
        var offered = Tx.<java.util.List<Location>>call(c -> new MasterDataDao().locations(c));
        assertTrue(offered.stream().anyMatch(l -> l.id().equals(locId)));
        assertTrue(offered.stream().noneMatch(l -> l.id().equals(retiredId)));
    }

    @Test
    void aMobileShiftRecordsWhereItWas() {
        Location here = Tx.<java.util.List<Location>>call(c -> new MasterDataDao().locations(c)).stream().filter(l -> l.id().equals(locId)).findFirst().orElseThrow();
        Shift s = shifts.start(mobSup, mobile, here);
        assertEquals(locId, s.locationId());
        assertEquals("Kisumu border " + N, s.locationName());
        assertTrue(Tx.<Integer>call(c -> {
            try (var ps = c.prepareStatement("SELECT count(*) FROM sync_outbox WHERE entity_type = 'SHIFT' AND entity_id = ? AND payload::text LIKE ?")) {
                ps.setString(1, s.id().toString());
                ps.setString(2, "%Kisumu border " + N + "%");
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
            }
        }) >= 1, "the shift, with its location, is sent to head office");
        shifts.end(mobSup, s, "done");
    }

    @Test
    void aFixedSiteNeedsNoLocationButMayGiveOne() {
        Shift s = shifts.start(fixSup, fixed);
        assertNull(s.locationId());
        shifts.end(fixSup, s, null);
        Location here = Tx.<java.util.List<Location>>call(c -> new MasterDataDao().locations(c)).stream().filter(l -> l.id().equals(locId)).findFirst().orElseThrow();
        Shift t = shifts.start(fixSup, fixed, here);
        assertEquals(locId, t.locationId());
        shifts.end(fixSup, t, null);
    }
}
