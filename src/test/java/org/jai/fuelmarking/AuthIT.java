package org.jai.fuelmarking;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;
import org.jai.fuelmarking.security.PinHasher;
import org.jai.fuelmarking.service.AuthService;
import org.jai.fuelmarking.service.AuthService.AuthException;
import org.jai.fuelmarking.sync.MasterDataApplier;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthIT {
    static final String N = UUID.randomUUID().toString().substring(0, 6);
    static final Site site = new Site(UUID.randomUUID(), "AU" + N, "Auth Site " + N, "FIXED");
    // produced by Python's hashlib.pbkdf2_hmac for PIN 246810 - independent of PinHasher
    static final String PYTHON_HASH = "pbkdf2_sha256$210000$AAECAwQFBgcICQoLDA0ODw==$5YH+YySKOVGeQCRumOw7TtXW9ntIl48PR8AzqX1XvXc=";

    final AuthService auth = new AuthService();

    @BeforeAll
    static void seed() {
        Tx.run(c -> { new MasterDataDao().upsertSite(c, site); return null; });
    }

    UUID user(String pinHash, boolean active) {
        UUID id = UUID.randomUUID();
        Tx.run(c -> {
            try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id, active, pin_hash) VALUES (?,?,?,?,?,?,?)")) {
                ps.setObject(1, id); ps.setString(2, "au" + id.toString().substring(0, 8)); ps.setString(3, "Auth User");
                ps.setString(4, Role.SUPERVISOR.name()); ps.setObject(5, site.id()); ps.setBoolean(6, active); ps.setString(7, pinHash);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    static String message(Runnable r) { return assertThrows(AuthException.class, r::run).getMessage(); }

    static long scalar(String sql, Object... args) {
        return Tx.call(c -> {
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
            }
        });
    }

    @Test
    void hashingIsSaltedAndCompatibleWithAnIndependentImplementation() {
        assertTrue(PinHasher.verify("246810", PYTHON_HASH));
        assertFalse(PinHasher.verify("246811", PYTHON_HASH));
        assertFalse(PinHasher.verify("", PYTHON_HASH));
        assertFalse(PinHasher.verify("246810", null));
        assertFalse(PinHasher.verify("246810", "pbkdf2_sha256$1$x$y"));
        assertFalse(PinHasher.verify("246810", "plaintext"));
        String a = PinHasher.hash("246810"), b = PinHasher.hash("246810");
        assertNotEquals(a, b);
        assertTrue(PinHasher.verify("246810", a) && PinHasher.verify("246810", b));
    }

    @Test
    void correctPinSignsInAndReturnsTheUser() {
        UUID id = user(PYTHON_HASH, true);
        AppUser u = auth.authenticate(id, "246810");
        assertEquals(id, u.id());
        assertEquals(Role.SUPERVISOR, u.role());
        assertEquals(site.id(), u.siteId());
    }

    @Test
    void wrongPinCountsDownThenLocksEvenAgainstTheCorrectPin() {
        UUID id = user(PYTHON_HASH, true);
        assertTrue(message(() -> auth.authenticate(id, "000001")).contains("4 attempt"));
        assertTrue(message(() -> new AuthService().authenticate(id, "000002")).contains("3 attempt")); // counter survives a "restart"
        auth.authenticate(id, "246810");                                                              // success resets the counter
        assertEquals(0, scalar("SELECT failed_pin_attempts FROM app_users WHERE id = ?", id));

        for (int i = 1; i < AuthService.MAX_ATTEMPTS; i++) assertTrue(message(() -> auth.authenticate(id, "111222")).contains("Incorrect"));
        assertTrue(message(() -> auth.authenticate(id, "111222")).contains("locked"));
        assertTrue(message(() -> auth.authenticate(id, "246810")).contains("Try again at"), "correct PIN must not bypass the lock");

        // the lock expires
        Tx.run(c -> { c.createStatement().executeUpdate("UPDATE app_users SET locked_until = now() - interval '1 minute' WHERE id = '" + id + "'"); return null; });
        assertEquals(id, auth.authenticate(id, "246810").id());
        assertEquals(0, scalar("SELECT count(*) FROM app_users WHERE id = ? AND locked_until IS NOT NULL", id));
    }

    @Test
    void usersWithoutAPinOrInactiveOrUnknownCannotSignIn() {
        assertTrue(message(() -> auth.authenticate(user(null, true), "246810")).contains("No PIN"));
        assertEquals("Unknown user", message(() -> auth.authenticate(user(PYTHON_HASH, false), "246810")));
        assertEquals("Unknown user", message(() -> auth.authenticate(UUID.randomUUID(), "246810")));
        assertEquals("Enter your PIN", message(() -> auth.authenticate(UUID.randomUUID(), " ")));
    }

    @Test
    void replicatedPinResetClearsTheLockButAnUnchangedPinDoesNot() {
        UUID id = user(PYTHON_HASH, true);
        for (int i = 0; i < AuthService.MAX_ATTEMPTS; i++) assertThrows(AuthException.class, () -> auth.authenticate(id, "999888"));
        assertEquals(1, scalar("SELECT count(*) FROM app_users WHERE id = ? AND locked_until IS NOT NULL", id));
        String username = "au" + id.toString().substring(0, 8);

        String sameHashMessage = "{\"type\":\"USER\",\"op\":\"UPSERT\",\"payload\":{\"id\":\"" + id + "\",\"username\":\"" + username
                + "\",\"fullName\":\"Auth User\",\"role\":\"SUPERVISOR\",\"siteId\":\"" + site.id() + "\",\"active\":true,\"pinHash\":\"" + PYTHON_HASH + "\"}}";
        Tx.run(c -> { new MasterDataApplier().apply(c, sameHashMessage); return null; });
        assertEquals(1, scalar("SELECT count(*) FROM app_users WHERE id = ? AND locked_until IS NOT NULL", id), "a re-sync must not unlock");

        String newHash = PinHasher.hash("135790");
        String reset = sameHashMessage.replace(PYTHON_HASH, newHash);
        Tx.run(c -> { new MasterDataApplier().apply(c, reset); return null; });
        assertEquals(0, scalar("SELECT count(*) FROM app_users WHERE id = ? AND locked_until IS NOT NULL", id));
        assertEquals(id, auth.authenticate(id, "135790").id());
        assertTrue(message(() -> auth.authenticate(id, "246810")).contains("Incorrect"), "old PIN stops working");
    }
}
