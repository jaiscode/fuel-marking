package org.jai.fuelmarking.central;

import org.jai.fuelmarking.central.auth.AuthProperties;
import org.jai.fuelmarking.central.auth.RoleConverter;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Role claim shapes used by the common providers. */
class RoleConverterTest {
    static List<String> roles(String claimPath, Map<String, Object> claims) {
        AuthProperties p = new AuthProperties("c", "openid", claimPath, "fuelmarking-admin", "fuelmarking-viewer", "");
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
        return new RoleConverter(p).convert(jwt).stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }

    @Test void flatArrayClaimAsUsedByEntraId() {
        assertEquals(List.of("ROLE_ADMIN", "ROLE_VIEWER"), roles("roles", Map.of("sub", "x", "roles", List.of("fuelmarking-admin", "fuelmarking-viewer", "other"))));
    }

    @Test void nestedClaimAsUsedByKeycloak() {
        assertEquals(List.of("ROLE_VIEWER"), roles("realm_access.roles", Map.of("realm_access", Map.of("roles", List.of("fuelmarking-viewer", "offline_access")))));
    }

    @Test void spaceSeparatedStringClaim() {
        assertEquals(List.of("ROLE_ADMIN"), roles("scp", Map.of("scp", "openid fuelmarking-admin")));
    }

    @Test void literalClaimNameWithDots() {
        assertEquals(List.of("ROLE_ADMIN"), roles("https://example.org/roles", Map.of("https://example.org/roles", List.of("fuelmarking-admin"))));
    }

    @Test void missingOrMalformedClaimGivesNoAccess() {
        assertEquals(List.of(), roles("roles", Map.of("sub", "x")));
        assertEquals(List.of(), roles("realm_access.roles", Map.of("realm_access", "oops")));
        assertEquals(List.of(), roles("roles", Map.of("roles", 42)));
        assertEquals(List.of(), roles("roles", Map.of("roles", List.of("FUELMARKING-ADMIN")))); // role names are case sensitive
    }
}
