package org.jai.fuelmarking.central;

import no.nav.security.mock.oauth2.MockOAuth2Server;
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback;

import java.util.List;
import java.util.Map;

/** A real OIDC provider (discovery, JWKS, signed tokens) running inside the test JVM. */
final class TestIdp {
    static final String AUDIENCE = "fuelmarking-api";
    static final MockOAuth2Server SERVER = start();

    private TestIdp() {}

    private static MockOAuth2Server start() {
        MockOAuth2Server s = new MockOAuth2Server();
        s.start();
        Runtime.getRuntime().addShutdownHook(new Thread(s::shutdown));
        return s;
    }

    static String issuer() { return SERVER.issuerUrl("default").toString(); }

    /** Signed by the trusted provider, issued for our API. */
    static String token(String subject, List<String> roles) { return token("default", subject, List.of(AUDIENCE), roles, 3600); }

    static String token(String issuerId, String subject, List<String> audience, List<String> roles, long ttlSeconds) {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("name", subject + " Display");
        claims.put("email", subject + "@example.org");
        if (roles != null) claims.put("roles", roles);
        return SERVER.issueToken(issuerId, "fuelmarking-web",
                new DefaultOAuth2TokenCallback(issuerId, subject, "JWT", audience, claims, ttlSeconds)).serialize();
    }

    static String admin() { return token("alice", List.of("fuelmarking-admin")); }

    static String viewer() { return token("victor", List.of("fuelmarking-viewer")); }
}
