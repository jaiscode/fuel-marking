package org.jai.fuelmarking.central.master;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ApiKeys {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public ApiKeys(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Issues (or rotates) a site's key. The plaintext is returned once and never stored. */
    public String issue(UUID siteId) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("""
                INSERT INTO site_credentials(site_id, api_key_hash) VALUES (?, ?)
                ON CONFLICT (site_id) DO UPDATE SET api_key_hash = EXCLUDED.api_key_hash, created_at = now()""",
                siteId, hash(key));
        return key;
    }

    public boolean matches(UUID siteId, String key) {
        List<String> stored = jdbc.queryForList("SELECT api_key_hash FROM site_credentials WHERE site_id = ?", String.class, siteId);
        return !stored.isEmpty() && MessageDigest.isEqual(
                stored.get(0).getBytes(StandardCharsets.UTF_8), hash(key).getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
