package org.jai.fuelmarking.central.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PIN hashing: PBKDF2-HMAC-SHA256, random 16-byte salt, stored as "pbkdf2_sha256$iterations$salt$hash" (base64).
 * The central backend and the desktop app carry identical copies of this class; keep the format in sync.
 */
public final class PinHasher {
    private static final String PREFIX = "pbkdf2_sha256";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PinHasher() {}

    public static String hash(String pin) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return PREFIX + "$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(pin, salt, ITERATIONS));
    }

    /** False for a wrong PIN or a malformed/unsupported stored value. */
    public static boolean verify(String pin, String stored) {
        if (pin == null || stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !PREFIX.equals(p[0])) return false;
        try {
            int iterations = Integer.parseInt(p[1]);
            if (iterations < 1_000 || iterations > 5_000_000) return false;
            byte[] expected = Base64.getDecoder().decode(p[3]);
            return MessageDigest.isEqual(expected, derive(pin, Base64.getDecoder().decode(p[2]), iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String pin, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
