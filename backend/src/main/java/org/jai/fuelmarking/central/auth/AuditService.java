package org.jai.fuelmarking.central.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Append-only record of who changed master data. Call inside the same transaction as the change. */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Never put secrets (PINs, API keys) in {@code detail}. */
    public void record(String action, String target, String detail) {
        jdbc.update("INSERT INTO audit_log(actor, actor_subject, action, target, detail) VALUES (?,?,?,?,?)",
                actorName(), actorSubject(), action, target, detail);
    }

    /** For a non-person actor with no sign-in, e.g. {@code "site <uuid>"} for a site's sync push. Never put secrets in {@code detail}. */
    public void recordAs(String actor, String action, String target, String detail) {
        jdbc.update("INSERT INTO audit_log(actor, actor_subject, action, target, detail) VALUES (?,?,?,?,?)",
                actor, actor, action, target, detail);
    }

    /**
     * Records once per {@code dedupeKey} (a partial unique index); returns false when an entry with that key already exists.
     * Never put secrets in {@code detail} or the key.
     */
    public boolean recordOnce(String actor, String action, String target, String detail, String dedupeKey) {
        return jdbc.update("""
                INSERT INTO audit_log(actor, actor_subject, action, target, detail, dedupe_key) VALUES (?,?,?,?,?,?)
                ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING""",
                actor, actor, action, target, detail, dedupeKey) > 0;
    }

    public List<Map<String, Object>> recent(int limit) { return recent(limit, null); }

    /** Newest first; {@code action} (optional) keeps only that action, e.g. so master-data changes are not buried by SYNC_CONFLICT rows. */
    public List<Map<String, Object>> recent(int limit, String action) {
        int n = Math.max(1, Math.min(limit, 500));
        if (action == null || action.isBlank())
            return jdbc.queryForList("SELECT id, at, actor, action, target, detail FROM audit_log ORDER BY id DESC LIMIT ?", n);
        return jdbc.queryForList("SELECT id, at, actor, action, target, detail FROM audit_log WHERE action = ? ORDER BY id DESC LIMIT ?", action, n);
    }

    private static String actorName() {
        Jwt jwt = jwt();
        return jwt == null ? "system" : MeController.displayName(jwt);
    }

    private static String actorSubject() {
        Jwt jwt = jwt();
        return jwt == null ? "system" : jwt.getSubject();
    }

    private static Jwt jwt() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a instanceof JwtAuthenticationToken t ? t.getToken() : null;
    }
}
