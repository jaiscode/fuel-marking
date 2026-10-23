package org.jai.fuelmarking.central;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Who may call what: token validation and role enforcement with real signed JWTs. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:postgresql://localhost:55432/fuel_central_test",
        "spring.datasource.password=x",
        "fuelmarking.master-data-drain-enabled=false",   // only the suites that test delivery drain the shared outbox
        "fuelmarking.auth.audience=fuelmarking-api"})
@EmbeddedKafka(partitions = 1)
class AuthorizationIT {
    @DynamicPropertySource
    static void idp(DynamicPropertyRegistry r) { r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestIdp::issuer); }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    ResponseEntity<String> get(String url, String bearer) {
        HttpHeaders h = new HttpHeaders();
        if (bearer != null) h.setBearerAuth(bearer);
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    ResponseEntity<String> post(String url, String bearer, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(bearer);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    @Test
    void noTokenOrAnInvalidOneIsUnauthorised() {
        assertEquals(401, get("/api/web/overview", null).getStatusCode().value());
        assertEquals(401, get("/api/admin/sites", null).getStatusCode().value());
        assertEquals(401, get("/api/web/overview", "garbage").getStatusCode().value());
        // a well-formed token whose payload was edited after signing (here: promoting oneself to admin)
        String t = TestIdp.admin();
        String[] parts = t.split("\\.");
        String forged = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"mallory\",\"roles\":[\"fuelmarking-admin\"]}".getBytes()) + "." + parts[2];
        assertEquals(401, get("/api/admin/sites", forged).getStatusCode().value());
    }

    @Test
    void expiredWrongIssuerAndWrongAudienceTokensAreRejected() {
        List<String> admin = List.of("fuelmarking-admin");
        assertEquals(401, get("/api/web/overview", TestIdp.token("default", "a", List.of(TestIdp.AUDIENCE), admin, -60)).getStatusCode().value(), "expired");
        assertEquals(401, get("/api/web/overview", TestIdp.token("other-realm", "a", List.of(TestIdp.AUDIENCE), admin, 3600)).getStatusCode().value(), "issuer we do not trust");
        assertEquals(401, get("/api/web/overview", TestIdp.token("default", "a", List.of("some-other-api"), admin, 3600)).getStatusCode().value(), "token meant for another API");
        assertEquals(200, get("/api/web/overview", TestIdp.token("default", "a", List.of(TestIdp.AUDIENCE), admin, 3600)).getStatusCode().value());
    }

    @Test
    void rolesDecideWhatACallerMayDo() {
        // reading reports: viewer and admin yes, nobody-in-particular no
        assertEquals(200, get("/api/web/overview", TestIdp.viewer()).getStatusCode().value());
        assertEquals(200, get("/api/web/overview", TestIdp.admin()).getStatusCode().value());
        assertEquals(403, get("/api/web/overview", TestIdp.token("nobody", List.of())).getStatusCode().value());
        assertEquals(403, get("/api/web/overview", TestIdp.token("nobody", List.of("some-unrelated-role"))).getStatusCode().value());
        assertEquals(403, get("/api/web/overview", TestIdp.token("nobody", null)).getStatusCode().value(), "no roles claim at all");

        // administration: admin only - a viewer can read everything but change nothing
        assertEquals(200, get("/api/admin/sites", TestIdp.admin()).getStatusCode().value());
        assertEquals(403, get("/api/admin/sites", TestIdp.viewer()).getStatusCode().value());
        assertEquals(403, post("/api/admin/sites", TestIdp.viewer(), Map.of("code", "VX", "name", "Nope", "siteType", "FIXED")).getStatusCode().value());
        assertEquals(403, post("/api/admin/master-data/republish", TestIdp.viewer(), Map.of()).getStatusCode().value());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marking_sites WHERE code = 'VX'", Integer.class));
    }

    @Test
    void viewersGetTheNamesTheirFiltersNeedWithoutTouchingAdministration() throws Exception {
        ResponseEntity<String> r = get("/api/web/lookups", TestIdp.viewer());
        assertEquals(200, r.getStatusCode().value());
        JsonNode l = mapper.readTree(r.getBody());
        assertTrue(l.has("sites") && l.has("omcs"));
        assertFalse(r.getBody().contains("pin") || r.getBody().contains("apiKey"), "lookups carry names only");
        assertEquals(401, get("/api/web/lookups", null).getStatusCode().value());
        assertEquals(403, get("/api/web/lookups", TestIdp.token("nobody", List.of())).getStatusCode().value());
    }

    @Test
    void clientRoutesServeTheAppSoReloadsAndProviderRedirectsWork() {
        for (String path : List.of("/signed-out", "/no-access", "/auth-error", "/markings", "/markings/0b1c2d3e-0000-0000-0000-000000000000",
                "/inventory", "/incidents", "/shifts", "/transfers", "/admin/sites", "/admin/omcs", "/admin/users")) {
            ResponseEntity<String> r = get(path, null);
            assertEquals(200, r.getStatusCode().value(), path);
            assertTrue(r.getBody().contains("<app-root"), path + " should serve the Angular shell");
        }
        // anything else that is not the API or a known page stays closed
        assertEquals(403, get("/etc/passwd", null).getStatusCode().value());
        assertEquals(403, get("/api/unknown", null).getStatusCode().value());
    }

    @Test
    void healthIsPublicForOrchestratorsButNothingElseIsExposed() {
        ResponseEntity<String> h = get("/actuator/health", null);
        assertEquals(200, h.getStatusCode().value());
        assertTrue(h.getBody().contains("\"UP\""), h.getBody());
        assertFalse(h.getBody().contains("jdbc") || h.getBody().contains("postgres"), "no internals in the public health response");
        for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/configprops"))
            assertTrue(get(path, null).getStatusCode().value() == 403 || get(path, null).getStatusCode().value() == 404, path);
    }

    @Test
    void siteApiKeysAndPeopleTokensAreNotInterchangeable() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestIdp.admin());
        // a person's token does not authenticate a site on the sync API...
        assertEquals(401, rest.exchange("/api/sync/pull", HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode().value());
        // ...and the old shared-password style of admin login is gone
        HttpHeaders basic = new HttpHeaders();
        basic.setBasicAuth("admin", "admin");
        assertEquals(401, rest.exchange("/api/admin/sites", HttpMethod.GET, new HttpEntity<>(basic), String.class).getStatusCode().value());
    }

    @Test
    void theWebAppCanDiscoverHowToSignInWithoutAnyLogin() throws Exception {
        ResponseEntity<String> r = get("/api/public/auth-config", null);
        assertEquals(200, r.getStatusCode().value());
        JsonNode c = mapper.readTree(r.getBody());
        assertEquals(TestIdp.issuer(), c.get("issuer").asText());
        assertEquals("fuelmarking-web", c.get("clientId").asText());
        assertTrue(c.get("scope").asText().contains("openid"));
        assertEquals(TestIdp.AUDIENCE, c.get("audience").asText());
        assertFalse(r.getBody().toLowerCase().contains("secret"));
    }

    @Test
    void meReportsTheCallerAndTheirRoles() throws Exception {
        JsonNode admin = mapper.readTree(get("/api/web/me", TestIdp.admin()).getBody());
        assertEquals("alice", admin.get("subject").asText());
        assertEquals("alice Display", admin.get("name").asText());
        assertTrue(admin.get("admin").asBoolean());
        JsonNode viewer = mapper.readTree(get("/api/web/me", TestIdp.viewer()).getBody());
        assertFalse(viewer.get("admin").asBoolean());
        assertEquals("viewer", viewer.get("roles").get(0).asText());
    }

    @Test
    void adminActionsAreAuditedWithoutLeakingSecrets() throws Exception {
        String code = "AU" + System.nanoTime() % 1_000_000;
        ResponseEntity<String> created = post("/api/admin/sites", TestIdp.token("auditor", List.of("fuelmarking-admin")),
                Map.of("code", code, "name", "Audited " + code, "siteType", "FIXED"));
        assertEquals(201, created.getStatusCode().value());
        String siteId = mapper.readTree(created.getBody()).get("id").asText();
        ResponseEntity<String> key = post("/api/admin/sites/" + siteId + "/api-key", TestIdp.token("auditor", List.of("fuelmarking-admin")), Map.of());
        String apiKey = mapper.readTree(key.getBody()).get("apiKey").asText();

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT actor, actor_subject, action, target, detail FROM audit_log WHERE target = ? ORDER BY id", siteId);
        assertEquals(List.of("SITE_CREATE", "API_KEY_ISSUED"), rows.stream().map(r -> r.get("action")).toList());
        assertEquals("auditor", rows.get(0).get("actor_subject"));
        assertEquals("auditor Display", rows.get(0).get("actor"));
        assertFalse(rows.toString().contains(apiKey), "the API key must never be written to the audit log");

        // and admins can read it back over the API, viewers cannot
        assertEquals(200, get("/api/admin/audit?limit=5", TestIdp.admin()).getStatusCode().value());
        assertEquals(403, get("/api/admin/audit?limit=5", TestIdp.viewer()).getStatusCode().value());
    }
}
