package org.jai.fuelmarking.central.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jai.fuelmarking.central.master.ApiKeys;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Authenticates a marking site by X-Site-Id + X-Site-Key. The authenticated principal is the site's UUID. */
public class SiteKeyFilter extends OncePerRequestFilter {
    private final ApiKeys apiKeys;

    public SiteKeyFilter(ApiKeys apiKeys) { this.apiKeys = apiKeys; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader("X-Site-Id");
        String key = req.getHeader("X-Site-Key");
        if (id != null && key != null) {
            try {
                UUID siteId = UUID.fromString(id);
                if (apiKeys.matches(siteId, key)) {
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            siteId, null, List.of(new SimpleGrantedAuthority("ROLE_SITE"))));
                }
            } catch (IllegalArgumentException ignored) {
                // malformed site id: stay unauthenticated
            }
        }
        chain.doFilter(req, res);
    }
}
