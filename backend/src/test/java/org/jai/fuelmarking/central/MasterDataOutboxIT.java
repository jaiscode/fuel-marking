package org.jai.fuelmarking.central;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jai.fuelmarking.central.master.MasterDataOutbox;
import org.jai.fuelmarking.central.master.MasterDataSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** The master-data outbox with a controllable stand-in for the Kafka connection. */
@org.springframework.test.context.TestPropertySource(locations = "classpath:it-database.properties")   // the shared test database settings
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "fuelmarking.master-data-drain-ms=150",
        "fuelmarking.auth.audience=fuelmarking-api"})
@EmbeddedKafka(partitions = 1)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS) // stop its background drain when done
@Import(MasterDataOutboxIT.FakeSinkConfig.class)
class MasterDataOutboxIT {
    static class FakeSink implements MasterDataSink {
        volatile boolean down;
        volatile Exception failure;                // when set, fail with exactly this (e.g. a wrapped authentication error)
        volatile int failOnCall = -1;           // fail exactly this (1-based) call once, to test mid-batch failure
        private final AtomicIntegerHolder calls = new AtomicIntegerHolder();
        final List<String> delivered = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(String key, String value) throws Exception {
            int n = calls.next();
            if (failure != null) throw failure;
            if (down) throw new IllegalStateException("broker down");
            if (n == failOnCall) { failOnCall = -1; throw new IllegalStateException("transient failure"); }
            delivered.add(key);
        }

        void reset() { failure = null; down = false; failOnCall = -1; calls.reset(); delivered.clear(); }
    }

    static class AtomicIntegerHolder {
        private final java.util.concurrent.atomic.AtomicInteger i = new java.util.concurrent.atomic.AtomicInteger();
        int next() { return i.incrementAndGet(); }
        void reset() { i.set(0); }
    }

    @TestConfiguration
    static class FakeSinkConfig {
        @Bean @Primary FakeSink fakeSink() { return new FakeSink(); }
    }

    @DynamicPropertySource
    static void idp(DynamicPropertyRegistry r) { r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestIdp::issuer); }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired FakeSink sink;
    @Autowired MasterDataOutbox outbox;
    @Autowired org.jai.fuelmarking.central.master.MasterDataService service;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void settle() throws Exception {
        sink.down = true;                         // park the background drain while we set up
        sink.down = false;
        awaitDrained();
        sink.reset();
    }

    HttpHeaders admin() { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(TestIdp.admin()); return h; }

    JsonNode post(String url, Object body, int status) {
        ResponseEntity<String> r = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, admin()), String.class);
        assertEquals(status, r.getStatusCode().value(), r.getBody());
        try { return r.getBody() == null || r.getBody().isBlank() ? null : mapper.readTree(r.getBody()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    JsonNode status() {
        try { return mapper.readTree(rest.exchange("/api/admin/master-data/status", HttpMethod.GET, new HttpEntity<>(admin()), String.class).getBody()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    void awaitDrained() throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            if (outbox.status().pending() == 0) return;
            Thread.sleep(100);
        }
        fail("outbox did not drain: " + outbox.status());
    }

    @Test
    void aBrokerOutageNeverSlowsAdminRequestsOrLosesChanges() throws Exception {
        sink.down = true;
        String n = String.valueOf(System.nanoTime() % 1_000_000);
        long t0 = System.nanoTime();
        UUID site = UUID.fromString(post("/api/admin/sites", Map.of("code", "OB" + n, "name", "Outbox " + n, "siteType", "FIXED"), 201).get("id").asText());
        UUID user = UUID.fromString(post("/api/admin/users", Map.of("username", "ob" + n, "fullName", "Ola", "role", "SUPERVISOR", "siteId", site), 201).get("id").asText());
        UUID omc = UUID.fromString(post("/api/admin/omcs", Map.of("code", "OO" + n, "name", "Outbox Oil " + n), 201).get("id").asText());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 3_000, "admin requests must not wait for Kafka; took " + ms + " ms");

        Thread.sleep(600); // several drain attempts fail
        JsonNode st = status();
        assertTrue(st.get("pending").asInt() >= 3, st.toString());
        assertEquals("broker down", st.get("lastError").asText());
        assertTrue(sink.delivered.isEmpty());

        sink.down = false;                          // broker is back
        awaitDrained();
        assertEquals(List.of("SITE:" + site, "USER:" + user, "OMC:" + omc),
                sink.delivered.stream().filter(k -> k.endsWith(site.toString()) || k.endsWith(user.toString()) || k.endsWith(omc.toString())).toList(),
                "delivered in the order the changes were made: the site before the user that references it");
        Thread.sleep(500);
        assertEquals(3, sink.delivered.size(), "nothing is delivered twice");
        JsonNode after = status();
        assertEquals(0, after.get("pending").asInt());
        assertTrue(after.get("lastError").isNull());
    }

    @Test
    void aFailureInTheMiddleKeepsOrderAndLosesNothing() throws Exception {
        String n = String.valueOf(System.nanoTime() % 1_000_000);
        sink.failOnCall = 2;                        // second message fails once
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            ids.add(UUID.fromString(post("/api/admin/omcs", Map.of("code", "M" + i + n, "name", "Order " + i + " " + n), 201).get("id").asText()));
        awaitDrained();
        assertEquals(ids.stream().map(id -> "OMC:" + id).toList(), sink.delivered);
    }

    @Test
    void concurrentDrainsNeverSendAnythingTwice() throws Exception {
        String n = String.valueOf(System.nanoTime() % 1_000_000);
        sink.down = true;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++)
            ids.add(UUID.fromString(post("/api/admin/omcs", Map.of("code", "C" + i + n, "name", "Concurrent " + i + " " + n), 201).get("id").asText()));
        sink.down = false;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) fs.add(pool.submit(outbox::drain));
        for (Future<Integer> f : fs) f.get(20, TimeUnit.SECONDS);
        pool.shutdown();
        awaitDrained();
        assertEquals(ids.stream().map(id -> "OMC:" + id).toList(), sink.delivered, "each once, in order");
    }

    @Test
    void republishQueuesEverythingAgainAndSaysSo() throws Exception {
        String n = String.valueOf(System.nanoTime() % 1_000_000);
        UUID site = UUID.fromString(post("/api/admin/sites", Map.of("code", "RP" + n, "name", "Replay " + n, "siteType", "MOBILE"), 201).get("id").asText());
        awaitDrained();
        sink.delivered.clear();
        int queued = post("/api/admin/master-data/republish", Map.of(), 200).get("published").asInt();
        assertTrue(queued >= 1);
        awaitDrained();
        assertTrue(sink.delivered.contains("SITE:" + site));
        assertEquals(queued, sink.delivered.size());
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'MASTER_DATA_REPUBLISH'", Integer.class) >= 1);
    }

    @Test
    void theStatusSaysWhyDeliveryIsStuckNotJustThatItFailed() throws Exception {
        // Kafka wraps the real problem (a refused login) several layers deep; "Send failed" alone would leave an operator guessing
        sink.failure = new java.util.concurrent.ExecutionException("Send failed",
                new org.springframework.kafka.KafkaException("Send failed",
                        new org.apache.kafka.common.errors.SaslAuthenticationException("Authentication failed during authentication due to invalid credentials with SASL mechanism SCRAM-SHA-512")));
        String n = String.valueOf(System.nanoTime() % 1_000_000);
        post("/api/admin/omcs", Map.of("code", "W" + n, "name", "Why " + n), 201);
        Thread.sleep(700);
        String error = status().get("lastError").asText();
        assertTrue(error.contains("Authentication failed") && error.contains("SCRAM-SHA-512"), "the root cause is shown: " + error);
        sink.failure = null;
        awaitDrained();
    }

    @Test
    void fuelTypesRumTypesAndJerricansAreMasterDataLikeTheRest() throws Exception {
        String n = String.valueOf(System.nanoTime() % 1_000_000);
        // the starting lists exist and are on their way to the sites
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM fuel_types WHERE code IN ('PETROL','DIESEL','KEROSENE')", Integer.class) == 3);

        // the starting fuels are marked with the standard rum until head office says otherwise, and the sites were told
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM fuel_types f JOIN rum_types r ON r.id = f.rum_type_id WHERE r.code = 'STANDARD' AND f.code IN ('PETROL','DIESEL','KEROSENE')", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM master_data_outbox WHERE msg_key LIKE 'FUEL_TYPE:%' AND payload LIKE '%rumTypeCode%STANDARD%'", Integer.class) >= 3);

        JsonNode rumJson = post("/api/admin/rum-types", Map.of("code", "RUM" + n, "name", "Dark rum " + n, "color", "#8b4513"), 201);
        UUID rum = UUID.fromString(rumJson.get("id").asText());
        // a fuel type is marked with one particular rum type: it is required, and it has to exist
        post("/api/admin/fuel-types", Map.of("code", "jet" + n, "name", "Jet fuel " + n), 400);
        post("/api/admin/fuel-types", Map.of("code", "jet" + n, "name", "Jet fuel " + n, "rumTypeId", UUID.randomUUID()), 400);
        UUID fuel = UUID.fromString(post("/api/admin/fuel-types", Map.of("code", "jet" + n, "name", "Jet fuel " + n, "rumTypeId", rum), 201).get("id").asText());
        assertEquals("JET" + n, jdbc.queryForObject("SELECT code FROM fuel_types WHERE id = ?", String.class, fuel), "codes are stored upper case");
        assertEquals(rum, jdbc.queryForObject("SELECT rum_type_id FROM fuel_types WHERE id = ?", UUID.class, fuel));
        JsonNode fuelMsg = mapper.readTree(jdbc.queryForObject("SELECT payload FROM master_data_outbox WHERE msg_key = ? ORDER BY id DESC LIMIT 1", String.class, "FUEL_TYPE:" + fuel)).get("payload");
        assertEquals("RUM" + n, fuelMsg.get("rumTypeCode").asText(), "the sites are told which rum type the fuel is marked with");
        assertEquals("#8B4513", rumJson.get("color").asText(), "colours are stored as upper-case #RRGGBB");
        assertEquals("#808080", post("/api/admin/rum-types", Map.of("code", "GREY" + n, "name", "No colour chosen " + n), 201).get("color").asText(), "grey when none is chosen");
        post("/api/admin/rum-types", Map.of("code", "BAD" + n, "name", "Bad colour " + n, "color", "brown"), 400);
        post("/api/admin/rum-types", Map.of("code", "BAD2" + n, "name", "Bad colour " + n, "color", "#12345"), 400);
        assertEquals("#00FF00", mapper.readTree(rest.exchange("/api/admin/rum-types/" + rum, HttpMethod.PUT, new HttpEntity<>(
                Map.of("code", "RUM" + n, "name", "Dark rum " + n, "color", "#00ff00", "active", true), admin()), String.class).getBody()).get("color").asText());
        assertEquals("#00FF00", jdbc.queryForObject("SELECT color FROM rum_types WHERE id = ?", String.class, rum));

        // registering a jerrican: id, container type, capacity (grams), rum type
        UUID metalType = jdbc.queryForObject("SELECT id FROM container_types WHERE code = 'JERRICAN_METAL'", UUID.class);
        UUID plasticType = jdbc.queryForObject("SELECT id FROM container_types WHERE code = 'JERRICAN_PLASTIC'", UUID.class);
        UUID ibcType = jdbc.queryForObject("SELECT id FROM container_types WHERE code = 'IBC'", UUID.class);
        UUID j = UUID.fromString(post("/api/admin/jerricans", Map.of("barcode", "JC-" + n, "containerTypeId", metalType, "capacityG", 18000, "rumTypeId", rum), 201).get("id").asText());
        awaitDrained();
        assertEquals(List.of("RUM_TYPE:" + rum, "FUEL_TYPE:" + fuel, "RUM_TYPE:" + rum, "JERRICAN:" + j),   // the rum type was created, the fuel type marked with it, then the rum recoloured
                sink.delivered.stream().filter(k -> k.endsWith(fuel.toString()) || k.endsWith(rum.toString()) || k.endsWith(j.toString())).toList());
        String payload = jdbc.queryForObject("SELECT payload FROM master_data_outbox WHERE msg_key = ? ORDER BY id DESC LIMIT 1", String.class, "JERRICAN:" + j);
        JsonNode msg = mapper.readTree(payload).get("payload");
        assertEquals("JC-" + n, msg.get("barcode").asText());
        assertEquals("JERRICAN_METAL", msg.get("containerTypeCode").asText(), "sites identify the container type by its code");
        assertEquals(18000, msg.get("capacityG").asDouble(), 0.001);
        assertEquals("RUM" + n, msg.get("rumTypeCode").asText(), "sites identify the rum type by its code");
        String rumMsg = jdbc.queryForObject("SELECT payload FROM master_data_outbox WHERE msg_key = ? ORDER BY id DESC LIMIT 1", String.class, "RUM_TYPE:" + rum);
        assertEquals("#00FF00", mapper.readTree(rumMsg).get("payload").get("color").asText(), "the colour travels to the sites with the rum type");

        // what is not acceptable
        assertEquals(409, rest.exchange("/api/admin/jerricans", HttpMethod.POST, new HttpEntity<>(
                Map.of("barcode", "JC-" + n, "containerTypeId", plasticType, "capacityG", 22000, "rumTypeId", rum), admin()), String.class).getStatusCode().value(), "ids are unique");
        post("/api/admin/jerricans", Map.of("barcode", "JB-" + n, "containerTypeId", UUID.randomUUID(), "capacityG", 1000, "rumTypeId", rum), 400);   // no such type
        post("/api/admin/jerricans", Map.of("barcode", "JB-" + n, "containerTypeId", ibcType, "capacityG", 1000, "rumTypeId", rum), 400);               // an IBC is not a jerrican
        post("/api/admin/jerricans", Map.of("barcode", "JB-" + n, "containerTypeId", plasticType, "capacityG", 0, "rumTypeId", rum), 400);
        post("/api/admin/jerricans", Map.of("barcode", "JB-" + n, "containerTypeId", plasticType, "capacityG", 22000, "rumTypeId", UUID.randomUUID()), 400);

        // a delivery registered in one go; one bad entry rejects the lot
        assertEquals(3, post("/api/admin/jerricans/bulk", Map.of("barcodes", List.of("D1-" + n, " D2-" + n + " ", "D3-" + n), "containerTypeId", plasticType,
                "capacityG", 22000, "rumTypeId", rum), 201).get("registered").asInt());
        post("/api/admin/jerricans/bulk", Map.of("barcodes", List.of("E1-" + n, "E1-" + n), "containerTypeId", plasticType, "capacityG", 22000, "rumTypeId", rum), 400);
        post("/api/admin/jerricans/bulk", Map.of("barcodes", List.of("E2-" + n, "D1-" + n), "containerTypeId", plasticType, "capacityG", 22000, "rumTypeId", rum), 409);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM registered_jerricans WHERE barcode IN ('E1-" + n + "','E2-" + n + "')", Integer.class));

        // the admin list shows the registered jerrican, and the web lookups offer the new types
        JsonNode list = mapper.readTree(rest.exchange("/api/admin/jerricans", HttpMethod.GET, new HttpEntity<>(admin()), String.class).getBody());
        JsonNode mine = null;
        for (JsonNode r : list) if (("JC-" + n).equals(r.get("barcode").asText())) mine = r;
        assertNotNull(mine);
        assertEquals("RUM" + n, mine.get("rumTypeCode").asText());
        assertEquals("#00FF00", mine.get("rumColor").asText());
        assertTrue(mine.get("status").isNull(), "no site has reported it yet");
        JsonNode lookups = mapper.readTree(rest.exchange("/api/web/lookups", HttpMethod.GET, new HttpEntity<>(admin()), String.class).getBody());
        assertTrue(lookups.get("fuelTypes").toString().contains("JET" + n) && lookups.get("rumTypes").toString().contains("RUM" + n));
        assertTrue(lookups.get("rumTypes").toString().contains("#00FF00"), "the web lookups carry the colour");

        // a site's report of the container (same barcode) shows up against the registration
        UUID site = UUID.fromString(post("/api/admin/sites", Map.of("code", "JS" + n, "name", "Jerrican site " + n, "siteType", "WAREHOUSE"), 201).get("id").asText());
        jdbc.update("INSERT INTO synced_entities(entity_type, entity_id, source_site_id, outbox_id, operation, payload, source_created_at) VALUES ('CONTAINER', ?, ?, 1, 'UPSERT', ?::jsonb, now())",
                UUID.randomUUID().toString(), site, "{\"barcode\":\"JC-" + n + "\",\"status\":\"SEALED\",\"siteId\":\"" + site + "\",\"currentVolumeMl\":17900}");
        list = mapper.readTree(rest.exchange("/api/admin/jerricans", HttpMethod.GET, new HttpEntity<>(admin()), String.class).getBody());
        for (JsonNode r : list) if (("JC-" + n).equals(r.get("barcode").asText())) mine = r;
        assertEquals("SEALED", mine.get("status").asText());
        assertEquals("Jerrican site " + n, mine.get("siteName").asText());
        assertEquals(17900, mine.get("volumeG").asDouble(), 0.001);
        jdbc.update("DELETE FROM synced_entities WHERE payload ->> 'barcode' = ?", "JC-" + n);

        // viewers read but never change
        HttpHeaders viewer = new HttpHeaders();
        viewer.setBearerAuth(TestIdp.viewer());
        assertEquals(403, rest.exchange("/api/admin/jerricans", HttpMethod.POST, new HttpEntity<>(
                Map.of("barcode", "V-" + n, "containerTypeId", plasticType, "capacityG", 22000, "rumTypeId", rum), viewer), String.class).getStatusCode().value());
        assertEquals(403, rest.exchange("/api/admin/fuel-types", HttpMethod.GET, new HttpEntity<>(viewer), String.class).getStatusCode().value());

        // republishing sends rum types before the jerricans that refer to them
        awaitDrained();
        sink.delivered.clear();
        post("/api/admin/master-data/republish", Map.of(), 200);
        awaitDrained();
        int lastRum = -1, firstJerrican = Integer.MAX_VALUE;
        for (int i = 0; i < sink.delivered.size(); i++) {
            if (sink.delivered.get(i).startsWith("RUM_TYPE:")) lastRum = i;
            if (sink.delivered.get(i).startsWith("JERRICAN:")) firstJerrican = Math.min(firstJerrican, i);
        }
        assertTrue(lastRum < firstJerrican, sink.delivered.toString());
    }

    @Test
    void containerTypesAreMasterDataWithSealsAndAUsualCapacity() throws Exception {
        String n = String.valueOf(System.nanoTime() % 1_000_000);
        // the starting list: bulk IBC and drum, jerricans plastic (1 seal, 22 kg) and metal (2 seals, 18 kg)
        JsonNode list = mapper.readTree(rest.exchange("/api/admin/container-types", HttpMethod.GET, new HttpEntity<>(admin()), String.class).getBody());
        Map<String, JsonNode> byCode = new HashMap<>();
        for (JsonNode t : list) byCode.put(t.get("code").asText(), t);
        assertEquals("BULK", byCode.get("IBC").get("category").asText());
        assertEquals("BULK", byCode.get("DRUM").get("category").asText());
        assertEquals(1, byCode.get("JERRICAN_PLASTIC").get("seals").asInt());
        assertEquals(22000, byCode.get("JERRICAN_PLASTIC").get("defaultCapacityG").asDouble(), 0.001);
        assertEquals(2, byCode.get("JERRICAN_METAL").get("seals").asInt());
        assertTrue(byCode.get("IBC").get("defaultCapacityG").isNull(), "IBC sizes vary");

        // a new type: delivered to the sites, offered in the web lookups
        JsonNode drum = post("/api/admin/container-types", Map.of("code", "tote" + n, "name", "Tote " + n, "category", "BULK", "seals", 0), 201);
        UUID tote = UUID.fromString(drum.get("id").asText());
        assertEquals("TOTE" + n, drum.get("code").asText());
        JsonNode tin = post("/api/admin/container-types", Map.of("code", "tin" + n, "name", "Tin " + n, "category", "JERRICAN", "seals", 1, "defaultCapacityG", 10000), 201);
        awaitDrained();
        assertTrue(sink.delivered.contains("CONTAINER_TYPE:" + tote) && sink.delivered.contains("CONTAINER_TYPE:" + tin.get("id").asText()));
        JsonNode msg = mapper.readTree(jdbc.queryForObject("SELECT payload FROM master_data_outbox WHERE msg_key = ? ORDER BY id DESC LIMIT 1", String.class, "CONTAINER_TYPE:" + tin.get("id").asText())).get("payload");
        assertEquals("JERRICAN", msg.get("category").asText());
        assertEquals(1, msg.get("seals").asInt());
        assertEquals(10000, msg.get("defaultCapacityG").asDouble(), 0.001);
        assertTrue(mapper.readTree(rest.exchange("/api/web/lookups", HttpMethod.GET, new HttpEntity<>(admin()), String.class).getBody()).get("containerTypes").toString().contains("TIN" + n));

        // what is not acceptable
        post("/api/admin/container-types", Map.of("code", "x1" + n, "name", "X", "category", "JERRICAN", "seals", 0), 400);      // a jerrican carries at least one seal
        post("/api/admin/container-types", Map.of("code", "x2" + n, "name", "X", "category", "BULK", "seals", 3), 400);
        post("/api/admin/container-types", Map.of("code", "x3" + n, "name", "X", "category", "TANK", "seals", 1), 400);
        post("/api/admin/container-types", Map.of("code", "x4" + n, "name", "X", "category", "BULK", "seals", 0, "defaultCapacityG", 0), 400);
        assertEquals(409, rest.exchange("/api/admin/container-types", HttpMethod.POST, new HttpEntity<>(
                Map.of("code", "TIN" + n, "name", "Again", "category", "JERRICAN", "seals", 1), admin()), String.class).getStatusCode().value());
        // the category (and code) of a type that containers use cannot change; its name, seals and capacity can
        assertEquals(400, rest.exchange("/api/admin/container-types/" + tote, HttpMethod.PUT, new HttpEntity<>(
                Map.of("code", "TOTE" + n, "name", "Tote", "category", "JERRICAN", "seals", 1, "active", true), admin()), String.class).getStatusCode().value());
        assertEquals(200, rest.exchange("/api/admin/container-types/" + tote, HttpMethod.PUT, new HttpEntity<>(
                Map.of("code", "TOTE" + n, "name", "Big tote", "category", "BULK", "seals", 2, "defaultCapacityG", 900000, "active", true), admin()), String.class).getStatusCode().value());
        assertEquals(2, jdbc.queryForObject("SELECT seals FROM container_types WHERE id = ?", Integer.class, tote));

        // a jerrican can be registered for the new jerrican type, and sites are told its code
        UUID rum = UUID.fromString(post("/api/admin/rum-types", Map.of("code", "TR" + n, "name", "Tin rum " + n), 201).get("id").asText());
        UUID j = UUID.fromString(post("/api/admin/jerricans", Map.of("barcode", "TJ-" + n, "containerTypeId", tin.get("id").asText(), "capacityG", 10000, "rumTypeId", rum), 201).get("id").asText());
        JsonNode jm = mapper.readTree(jdbc.queryForObject("SELECT payload FROM master_data_outbox WHERE msg_key = ? ORDER BY id DESC LIMIT 1", String.class, "JERRICAN:" + j)).get("payload");
        assertEquals("TIN" + n, jm.get("containerTypeCode").asText());
        awaitDrained();

        // the admin jerrican list names the type and says how many seals it carries
        JsonNode rows = mapper.readTree(rest.exchange("/api/admin/jerricans", HttpMethod.GET, new HttpEntity<>(admin()), String.class).getBody());
        JsonNode mine = null;
        for (JsonNode r : rows) if (("TJ-" + n).equals(r.get("barcode").asText())) mine = r;
        assertNotNull(mine);
        assertEquals("TIN" + n, mine.get("containerTypeCode").asText());
        assertEquals(1, mine.get("seals").asInt());

        // republishing sends container types and rum types before the jerricans that refer to them
        sink.delivered.clear();
        post("/api/admin/master-data/republish", Map.of(), 200);
        awaitDrained();
        int lastType = -1, firstJerrican = Integer.MAX_VALUE;
        for (int i = 0; i < sink.delivered.size(); i++) {
            if (sink.delivered.get(i).startsWith("CONTAINER_TYPE:") || sink.delivered.get(i).startsWith("RUM_TYPE:")) lastType = i;
            if (sink.delivered.get(i).startsWith("JERRICAN:")) firstJerrican = Math.min(firstJerrican, i);
        }
        assertTrue(lastType < firstJerrican, sink.delivered.toString());

        // viewers cannot change container types
        HttpHeaders viewer = new HttpHeaders();
        viewer.setBearerAuth(TestIdp.viewer());
        assertEquals(403, rest.exchange("/api/admin/container-types", HttpMethod.POST, new HttpEntity<>(
                Map.of("code", "v" + n, "name", "V", "category", "BULK", "seals", 0), viewer), String.class).getStatusCode().value());
    }

    @Test
    void theOutboxStatusIsAdminOnly() {
        HttpHeaders viewer = new HttpHeaders();
        viewer.setBearerAuth(TestIdp.viewer());
        assertEquals(403, rest.exchange("/api/admin/master-data/status", HttpMethod.GET, new HttpEntity<>(viewer), String.class).getStatusCode().value());
        assertEquals(401, rest.exchange("/api/admin/master-data/status", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class).getStatusCode().value());
    }

    @Test
    void locationsAreMasterDataLikeTheRest() throws Exception {
        String n = String.valueOf(System.nanoTime() % 1_000_000);
        post("/api/admin/locations", Map.of("code", "", "name", "No code " + n), 400);
        JsonNode created = post("/api/admin/locations", Map.of("code", "kb" + n, "name", "Kisumu border " + n), 201);
        UUID id = UUID.fromString(created.get("id").asText());
        assertEquals("KB" + n, jdbc.queryForObject("SELECT code FROM locations WHERE id = ?", String.class, id), "codes are stored upper case");
        JsonNode msg = mapper.readTree(jdbc.queryForObject("SELECT payload FROM master_data_outbox WHERE msg_key = ? ORDER BY id DESC LIMIT 1", String.class, "LOCATION:" + id)).get("payload");
        assertEquals("Kisumu border " + n, msg.get("name").asText(), "the sites are told");
        rest.exchange("/api/admin/locations/" + id, HttpMethod.PUT, new HttpEntity<>(Map.of("code", "KB" + n, "name", "Kisumu border " + n, "active", false), admin()), String.class);
        assertFalse(jdbc.queryForObject("SELECT active FROM locations WHERE id = ?", Boolean.class, id));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM master_data_outbox WHERE msg_key = ?", Integer.class, "LOCATION:" + id) >= 2, "a retirement is sent too");
        awaitDrained();
    }
}
