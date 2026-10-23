package org.jai.fuelmarking.central.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Who the backend believes the caller is. The web app uses this to decide what to show; the API enforces it anyway. */
@RestController
@RequestMapping("/api/web")
public class MeController {
    public record Me(String subject, String name, String email, boolean admin, List<String> roles) {}

    @GetMapping("/me")
    Me me(Authentication auth) {
        Jwt jwt = ((JwtAuthenticationToken) auth).getToken();
        List<String> roles = auth.getAuthorities().stream().map(a -> a.getAuthority().substring("ROLE_".length()).toLowerCase()).sorted().toList();
        return new Me(jwt.getSubject(), displayName(jwt), jwt.getClaimAsString("email"), roles.contains("admin"), roles);
    }

    /** Best human-readable name the token offers. */
    static String displayName(Jwt jwt) {
        for (String c : new String[]{"name", "preferred_username", "email"}) {
            String v = jwt.getClaimAsString(c);
            if (v != null && !v.isBlank()) return v;
        }
        return jwt.getSubject();
    }
}
