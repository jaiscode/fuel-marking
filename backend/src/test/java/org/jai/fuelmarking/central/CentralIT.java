package org.jai.fuelmarking.central;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:postgresql://localhost:55432/fuel_central_test",
        "spring.datasource.password=x",
        "fuelmarking.master-data-drain-ms=200",
        "fuelmarking.auth.audience=fuelmarking-api"})
@EmbeddedKafka(partitions = 1)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS) // stop its background drain when done
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CentralIT {
    static final String N = UUID.randomUUID().toString().substring(0, 6);
    static UUID whId, siteId;
    static String whKey, siteKey;

    @org.springframework.test.context.DynamicPropertySource
    static void idp(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestIdp::issuer);
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired EmbeddedKafkaBroker broker;

    HttpHeaders admin() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestIdp.admin());
        return h;
    }

    HttpHeaders site(UUID id, String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Site-Id", id.toString());
        h.set("X-Site-Key", key);
        return h;
    }

    JsonNode call(HttpMethod m, String url, HttpHeaders h, Object body, int expectedStatus) {
        ResponseEntity<String> r = rest.exchange(url, m, new HttpEntity<>(body, h), String.class);
        assertEquals(expectedStatus, r.getStatusCode().value(), r.getBody());
        try { return r.getBody() == null || r.getBody().isBlank() ? null : mapper.readTree(r.getBody()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    Map<String, Object> event(long id, String type, String entityId, Map<String, Object> payload) {
        return Map.of("outboxId", id, "entityType", type, "entityId", entityId, "operation", "UPSERT",
                "payload", payload, "createdAt", "2026-10-14T10:00:00");
    }

    Map<String, Object> container(String id, String barcode, UUID site, String status, int volume) {
        return new HashMap<>(Map.of("id", id, "barcode", barcode, "siteId", site.toString(), "status", status, "type", "JERRICAN_PLASTIC",
                "capacityMl", 1000.0, "currentVolumeMl", volume));
    }

    Map<String, Object> shift(String id, UUID site) {
        return Map.of("id", id, "siteId", site.toString(), "supervisorId", UUID.randomUUID().toString(), "status", "OPEN",
                "startedAt", "2026-10-14T08:00:00.123456", "openingRumMl", 0.0);
    }

    @Test @Order(1)
    void adminApiRequiresCredentialsAndPublishesMasterData() {
        call(HttpMethod.GET, "/api/admin/sites", new HttpHeaders(), null, 401);
        HttpHeaders bad = new HttpHeaders(); bad.setBearerAuth("not-a-jwt");
        call(HttpMethod.GET, "/api/admin/sites", bad, null, 401);

        Map<String, Object> wh = Map.of("code", "WH" + N, "name", "Warehouse " + N, "siteType", "WAREHOUSE");
        whId = UUID.fromString(call(HttpMethod.POST, "/api/admin/sites", admin(), wh, 201).get("id").asText());
        siteId = UUID.fromString(call(HttpMethod.POST, "/api/admin/sites", admin(),
                Map.of("code", "ST" + N, "name", "Site " + N, "siteType", "FIXED"), 201).get("id").asText());
        call(HttpMethod.POST, "/api/admin/sites", admin(), wh, 409); // duplicate code
        call(HttpMethod.POST, "/api/admin/sites", admin(), Map.of("code", "X", "name", "X", "siteType", "BOGUS"), 400);
        call(HttpMethod.POST, "/api/admin/users", admin(),
                Map.of("username", "u" + N, "fullName", "Una", "role", "GOD", "siteId", siteId), 400);
        call(HttpMethod.POST, "/api/admin/users", admin(),
                Map.of("username", "u" + N, "fullName", "Una", "role", "SUPERVISOR", "siteId", siteId), 201);
        call(HttpMethod.POST, "/api/admin/omcs", admin(), Map.of("code", "O" + N, "name", "Oil " + N), 201);

        whKey = call(HttpMethod.POST, "/api/admin/sites/" + whId + "/api-key", admin(), null, 200).get("apiKey").asText();
        siteKey = call(HttpMethod.POST, "/api/admin/sites/" + siteId + "/api-key", admin(), null, 200).get("apiKey").asText();
        assertNotEquals(whKey, siteKey);

        // everything arrived on the compacted topic, keyed by TYPE:id
        Map<String, Object> props = KafkaTestUtils.consumerProps("verify-" + N, "true", broker);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> c = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            c.subscribe(List.of("fuelmarking.master-data"));
            Set<String> keys = new HashSet<>();
            long end = System.currentTimeMillis() + 10_000;
            // other suites may have queued unrelated records first: wait for the ones this test created
            while (System.currentTimeMillis() < end && !(keys.contains("SITE:" + whId) && keys.contains("SITE:" + siteId)
                    && keys.stream().anyMatch(k -> k.startsWith("USER:")) && keys.stream().anyMatch(k -> k.startsWith("OMC:")))) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(500))) keys.add(r.key());
            }
            assertTrue(keys.contains("SITE:" + whId), keys.toString());
            assertTrue(keys.contains("SITE:" + siteId));
            assertTrue(keys.stream().anyMatch(k -> k.startsWith("USER:")));
            assertTrue(keys.stream().anyMatch(k -> k.startsWith("OMC:")));
        }
        assertEquals(401, rest.exchange("/api/admin/sites", HttpMethod.GET, new HttpEntity<>(site(whId, whKey)), String.class).getStatusCode().value());
    }

    @Test @Order(2)
    void syncRequiresValidSiteKey() {
        Map<String, Object> body = Map.of("events", List.of());
        call(HttpMethod.POST, "/api/sync/push", new HttpHeaders(), body, 401);
        call(HttpMethod.POST, "/api/sync/push", site(whId, "nope"), body, 401);
        call(HttpMethod.POST, "/api/sync/push", site(whId, siteKey), body, 401); // another site's key
        call(HttpMethod.POST, "/api/sync/push", site(whId, whKey), body, 200);
    }

    @Test @Order(3)
    void pushValidatesOwnershipShapeAndIsIdempotent() {
        String cid = UUID.randomUUID().toString();
        Map<String, Object> cont = container(cid, "J-" + N, whId, "IN_TRANSIT", 1000);
        String foreignShiftId = UUID.randomUUID().toString(), ownShiftId = UUID.randomUUID().toString();
        Map<String, Object> badTime = new HashMap<>(shift(UUID.randomUUID().toString(), whId));
        badTime.put("startedAt", "yesterday-ish");
        String badTimeId = (String) badTime.get("id");
        JsonNode r = call(HttpMethod.POST, "/api/sync/push", site(whId, whKey), Map.of("events", List.of(
                event(1, "CONTAINER", cid, cont),
                event(2, "SHIFT", foreignShiftId, shift(foreignShiftId, siteId)),     // another site's shift
                event(3, "WIDGET", "x", Map.of()),                                     // unknown type
                event(4, "SHIFT", ownShiftId, shift(ownShiftId, whId)),
                event(5, "SHIFT", badTimeId, badTime),                                 // would break the typed views
                event(6, "SHIFT", UUID.randomUUID().toString(), shift(UUID.randomUUID().toString(), whId)))), 200); // id mismatch
        assertEquals(List.of(1L, 4L), mapper.convertValue(r.get("acked"), new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {}));
        assertEquals(4, r.get("rejected").size());

        // same-site replay of an older version must not regress the stored state
        cont.put("currentVolumeMl", 5);
        call(HttpMethod.POST, "/api/sync/push", site(whId, whKey), Map.of("events", List.of(event(1, "CONTAINER", cid, cont))), 200);
        assertEquals(1000, jdbc.queryForObject(
                "SELECT (payload ->> 'currentVolumeMl')::int FROM synced_entities WHERE entity_type='CONTAINER' AND entity_id=?", Integer.class, cid));
    }

    @Test @Order(4)
    void transfersFlowToTheOtherSiteWithoutEchoing() {
        String tid = UUID.randomUUID().toString();
        String cid = UUID.randomUUID().toString();
        Map<String, Object> cont = container(cid, "J2-" + N, whId, "IN_TRANSIT", 1000);
        Map<String, Object> transfer = Map.of(
                "transfer", Map.of("id", tid, "fromSiteId", whId.toString(), "toSiteId", siteId.toString(), "status", "DISPATCHED", "dispatchedAt", "2026-10-14T10:00:00"),
                "items", List.of(Map.of("barcode", "J2-" + N, "received", false)));
        // a third party's transfer must be rejected
        String t9 = UUID.randomUUID().toString();
        Map<String, Object> notMine = Map.of("transfer", Map.of("id", t9, "fromSiteId", UUID.randomUUID().toString(), "toSiteId", UUID.randomUUID().toString(),
                "status", "DISPATCHED", "dispatchedAt", "2026-10-14T10:00:00"), "items", List.of());
        JsonNode r = call(HttpMethod.POST, "/api/sync/push", site(whId, whKey), Map.of("events", List.of(
                event(10, "CONTAINER", cid, cont), event(11, "TRANSFER", tid, transfer), event(12, "TRANSFER", t9, notMine))), 200);
        assertEquals(2, r.get("acked").size());
        assertEquals(1, r.get("rejected").size());

        JsonNode pulled = call(HttpMethod.GET, "/api/sync/pull?cursor=0", site(siteId, siteKey), null, 200);
        assertEquals(1, pulled.get("transfers").size());
        assertEquals(tid, pulled.get("transfers").get(0).path("transfer").path("id").asText());
        assertEquals(1, pulled.get("containers").size());
        assertEquals("J2-" + N, pulled.get("containers").get(0).path("barcode").asText());
        long cursor = pulled.get("cursor").asLong();
        assertTrue(cursor > 0);

        // nothing new after the cursor, and the sender never sees its own transfer echoed back
        assertEquals(0, call(HttpMethod.GET, "/api/sync/pull?cursor=" + cursor, site(siteId, siteKey), null, 200).get("transfers").size());
        assertEquals(0, call(HttpMethod.GET, "/api/sync/pull?cursor=0", site(whId, whKey), null, 200).get("transfers").size());

        // the destination receives; the warehouse now sees the update
        Map<String, Object> received = Map.of(
                "transfer", Map.of("id", tid, "fromSiteId", whId.toString(), "toSiteId", siteId.toString(), "status", "RECEIVED", "dispatchedAt", "2026-10-14T10:00:00"),
                "items", List.of(Map.of("barcode", "J2-" + N, "received", true)));
        call(HttpMethod.POST, "/api/sync/push", site(siteId, siteKey), Map.of("events", List.of(event(1, "TRANSFER", tid, received))), 200);
        JsonNode back = call(HttpMethod.GET, "/api/sync/pull?cursor=0", site(whId, whKey), null, 200);
        assertEquals(1, back.get("transfers").size());
        assertEquals("RECEIVED", back.get("transfers").get(0).path("transfer").path("status").asText());
    }

    @Test @Order(5)
    void pinsAreValidatedHashedAndReplicatedWithoutLeakingViaTheAdminApi() {
        JsonNode user = call(HttpMethod.POST, "/api/admin/users", admin(),
                Map.of("username", "pin" + N, "fullName", "Pia", "role", "DOSING_OPERATOR", "siteId", siteId), 201);
        String id = user.get("id").asText();
        for (String weak : List.of("123", "123456789", "abcd12", "1111", "123456", "654321", ""))
            call(HttpMethod.PUT, "/api/admin/users/" + id + "/pin", admin(), Map.of("pin", weak), 400);
        call(HttpMethod.PUT, "/api/admin/users/" + UUID.randomUUID() + "/pin", admin(), Map.of("pin", "246810"), 400);
        call(HttpMethod.PUT, "/api/admin/users/" + id + "/pin", new HttpHeaders(), Map.of("pin", "246810"), 401);
        call(HttpMethod.PUT, "/api/admin/users/" + id + "/pin", admin(), Map.of("pin", "246810"), 204);

        String stored = jdbc.queryForObject("SELECT pin_hash FROM app_users WHERE id = ?::uuid", String.class, id);
        assertTrue(stored.startsWith("pbkdf2_sha256$"));
        assertFalse(stored.contains("246810"));
        assertTrue(org.jai.fuelmarking.central.security.PinHasher.verify("246810", stored));
        assertFalse(org.jai.fuelmarking.central.security.PinHasher.verify("246811", stored));

        // the admin API never returns the hash
        assertFalse(rest.exchange("/api/admin/users", HttpMethod.GET, new HttpEntity<>(admin()), String.class).getBody().contains("pbkdf2"));

        // ...but the replicated Kafka record carries it, so sites can authenticate offline
        Map<String, Object> props = KafkaTestUtils.consumerProps("pin-verify-" + N, "true", broker);
        props.put("auto.offset.reset", "earliest");
        String published = null;
        try (Consumer<String, String> c = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            c.subscribe(List.of("fuelmarking.master-data"));
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end && (published == null || !published.contains("pinHash\":\"pbkdf2"))) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(500)))
                    if (("USER:" + id).equals(r.key())) published = r.value();
            }
        }
        assertNotNull(published);
        JsonNode payload;
        try { payload = mapper.readTree(published).get("payload"); } catch (Exception e) { throw new IllegalStateException(e); }
        assertEquals(stored, payload.get("pinHash").asText());
        assertFalse(published.contains("246810"));
    }

    @Test @Order(6)
    void hashFormatMatchesAnIndependentImplementation() {
        // produced by Python's hashlib.pbkdf2_hmac, not by this code
        String vector = "pbkdf2_sha256$210000$AAECAwQFBgcICQoLDA0ODw==$5YH+YySKOVGeQCRumOw7TtXW9ntIl48PR8AzqX1XvXc=";
        assertTrue(org.jai.fuelmarking.central.security.PinHasher.verify("246810", vector));
        assertFalse(org.jai.fuelmarking.central.security.PinHasher.verify("246811", vector));
        assertFalse(org.jai.fuelmarking.central.security.PinHasher.verify("246810", "garbage"));
        assertNotEquals(org.jai.fuelmarking.central.security.PinHasher.hash("246810"), org.jai.fuelmarking.central.security.PinHasher.hash("246810"), "salted");
    }
}
