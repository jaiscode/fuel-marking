package org.jai.fuelmarking.central;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The scheduled sequencer as deployed (enabled, on its timer): a pushed transfer becomes pullable without any test hook. */
@org.springframework.test.context.TestPropertySource(locations = "classpath:it-database.properties")   // the shared test database settings
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "fuelmarking.master-data-drain-enabled=false",
        "fuelmarking.sync-sequencer-ms=100",
        "fuelmarking.auth.audience=fuelmarking-api"})
@EmbeddedKafka(partitions = 1)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS) // stop its sequencer when done
class SequencerScheduleIT {
    static final String N = UUID.randomUUID().toString().substring(0, 6);

    @org.springframework.test.context.DynamicPropertySource
    static void idp(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestIdp::issuer);
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    JsonNode call(HttpMethod m, String url, HttpHeaders h, Object body, int status) {
        ResponseEntity<String> resp = rest.exchange(url, m, new HttpEntity<>(body, h), String.class);
        assertEquals(status, resp.getStatusCode().value(), resp.getBody());
        try { return resp.getBody() == null || resp.getBody().isBlank() ? null : mapper.readTree(resp.getBody()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    HttpHeaders admin() { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(TestIdp.admin()); return h; }

    HttpHeaders site(UUID id, String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Site-Id", id.toString());
        h.set("X-Site-Key", key);
        return h;
    }

    UUID newSite(String prefix) {
        return UUID.fromString(call(HttpMethod.POST, "/api/admin/sites", admin(),
                Map.of("code", prefix + N, "name", prefix + " " + N, "siteType", "FIXED"), 201).get("id").asText());
    }

    String apiKey(UUID id) { return call(HttpMethod.POST, "/api/admin/sites/" + id + "/api-key", admin(), null, 200).get("apiKey").asText(); }

    @Test
    void aPushedTransferIsNumberedOnScheduleAndPulled() throws Exception {
        UUID from = newSite("SF"), to = newSite("SD");
        String fromKey = apiKey(from), toKey = apiKey(to);
        String t = UUID.randomUUID().toString();
        Map<String, Object> transfer = Map.of("transfer", Map.of("id", t, "fromSiteId", from.toString(), "toSiteId", to.toString(),
                "status", "DISPATCHED", "dispatchedAt", "2026-10-14T10:00:00"), "items", List.of(Map.of("barcode", "SCH-" + N, "received", false)));
        JsonNode pushed = call(HttpMethod.POST, "/api/sync/push", site(from, fromKey), Map.of("events", List.of(Map.of("outboxId", 1,
                "entityType", "TRANSFER", "entityId", t, "operation", "UPSERT", "payload", transfer, "createdAt", "2026-10-14T10:00:00"))), 200);
        assertEquals(1, pushed.get("acked").size());

        long end = System.currentTimeMillis() + 10_000;
        while (jdbc.queryForObject("SELECT count(*) FROM synced_entities WHERE entity_type = 'TRANSFER' AND seq IS NULL", Long.class) > 0) {
            assertTrue(System.currentTimeMillis() < end, "the scheduled sequencer did not number the pushed transfer");
            Thread.sleep(50);
        }
        JsonNode pulled = call(HttpMethod.GET, "/api/sync/pull?cursor=0", site(to, toKey), null, 200);
        assertEquals(1, pulled.get("transfers").size());
        assertEquals(t, pulled.get("transfers").get(0).path("transfer").path("id").asText());
        assertTrue(pulled.get("cursor").asLong() > 0);
    }
}
