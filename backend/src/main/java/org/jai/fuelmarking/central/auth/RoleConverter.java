package org.jai.fuelmarking.central.auth;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Maps the identity provider's role names onto this application's two roles. Anything else in the token is ignored,
 * and a token without the roles claim gets no authorities (denied by default).
 */
public class RoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    public static final String ADMIN = "ROLE_ADMIN";
    public static final String VIEWER = "ROLE_VIEWER";

    private final AuthProperties props;

    public RoleConverter(AuthProperties props) { this.props = props; }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> out = new ArrayList<>();
        List<String> roles = roles(jwt.getClaims(), props.rolesClaim());
        if (roles.contains(props.adminRole())) out.add(new SimpleGrantedAuthority(ADMIN));
        if (roles.contains(props.viewerRole())) out.add(new SimpleGrantedAuthority(VIEWER));
        return out;
    }

    /** Resolves a possibly dotted claim path; accepts a JSON array or a space/comma separated string. */
    @SuppressWarnings("unchecked")
    static List<String> roles(Map<String, Object> claims, String path) {
        Object current = claims;
        // a literal claim name containing dots (e.g. a URL-style claim) wins over path traversal
        if (claims.containsKey(path)) {
            current = claims.get(path);
        } else {
            for (String part : path.split("\\.")) {
                if (!(current instanceof Map<?, ?> m) || !m.containsKey(part)) return List.of();
                current = ((Map<String, Object>) m).get(part);
            }
        }
        if (current instanceof Collection<?> c) return c.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        if (current instanceof String s && !s.isBlank()) return List.of(s.trim().split("[\\s,]+"));
        return List.of();
    }
}
