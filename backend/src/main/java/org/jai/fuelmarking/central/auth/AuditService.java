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

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("SELECT id, at, actor, action, target, detail FROM audit_log ORDER BY id DESC LIMIT ?", Math.max(1, Math.min(limit, 500)));
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
