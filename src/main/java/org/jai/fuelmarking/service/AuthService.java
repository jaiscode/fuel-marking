package org.jai.fuelmarking.service;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.model.Records.AppUser;
import org.jai.fuelmarking.model.Role;
import org.jai.fuelmarking.security.PinHasher;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Offline PIN authentication against the replicated PIN hash. Failed attempts are counted in the database so that a
 * restart does not reset the lockout. PINs are low-entropy: the lockout is the real protection, so keep it.
 */
public class AuthService {
    public static final int MAX_ATTEMPTS = 5;
    public static final Duration LOCK_TIME = Duration.ofMinutes(5);

    /** Authentication failure with a message safe to show to the user. */
    public static class AuthException extends RuntimeException {
        public AuthException(String message) { super(message); }
    }

    private record Outcome(AppUser user, String error) {}

    public AppUser authenticate(UUID userId, String pin) {
        if (pin == null || pin.isBlank()) throw new AuthException("Enter your PIN");
        Outcome o = Tx.call(c -> {
            // the verdict and the failure counter must commit together, so failures are returned, not thrown
            try (var ps = c.prepareStatement("SELECT username, full_name, role, site_id, pin_hash, failed_pin_attempts, locked_until " +
                    "FROM app_users WHERE id = ? AND active FOR UPDATE")) {
                ps.setObject(1, userId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) return new Outcome(null, "Unknown user");
                    AppUser user = new AppUser(userId, rs.getString(1), rs.getString(2), Role.valueOf(rs.getString(3)), rs.getObject(4, UUID.class));
                    String hash = rs.getString(5);
                    int failed = rs.getInt(6);
                    Timestamp lock = rs.getTimestamp(7);

                    if (lock != null && lock.toLocalDateTime().isAfter(LocalDateTime.now()))
                        return new Outcome(null, "Too many wrong PINs. Try again at " + lock.toLocalDateTime().toLocalTime().withNano(0).withSecond(0) + ".");
                    if (hash == null)
                        return new Outcome(null, "No PIN has been set for this user. Ask an administrator.");

                    if (PinHasher.verify(pin, hash)) {
                        if (failed > 0 || lock != null) update(c, userId, 0, null);
                        return new Outcome(user, null);
                    }
                    int now = failed + 1;
                    if (now >= MAX_ATTEMPTS) {
                        update(c, userId, 0, LocalDateTime.now().plus(LOCK_TIME));
                        return new Outcome(null, "Too many wrong PINs. This user is locked for " + LOCK_TIME.toMinutes() + " minutes.");
                    }
                    update(c, userId, now, null);
                    return new Outcome(null, "Incorrect PIN (" + (MAX_ATTEMPTS - now) + " attempt(s) left).");
                }
            }
        });
        if (o.error() != null) throw new AuthException(o.error());
        return o.user();
    }

    private static void update(java.sql.Connection c, UUID id, int failed, LocalDateTime lockedUntil) throws java.sql.SQLException {
        try (var ps = c.prepareStatement("UPDATE app_users SET failed_pin_attempts = ?, locked_until = ? WHERE id = ?")) {
            ps.setInt(1, failed);
            ps.setTimestamp(2, lockedUntil == null ? null : Timestamp.valueOf(lockedUntil));
            ps.setObject(3, id);
            ps.executeUpdate();
        }
    }
}
