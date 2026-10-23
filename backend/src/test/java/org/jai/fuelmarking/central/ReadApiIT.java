package org.jai.fuelmarking.central;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Pushes payloads captured from the real desktop app, then reads them back through the typed read API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:postgresql://localhost:55432/fuel_central_test",
        "spring.datasource.password=x",
        "fuelmarking.master-data-drain-enabled=false",   // only the suites that test delivery drain the shared outbox
        "fuelmarking.auth.audience=fuelmarking-api"})
@EmbeddedKafka(partitions = 1)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReadApiIT {
    static final String N = UUID.randomUUID().toString().substring(0, 6);
    static final String TODAY = LocalDate.now().toString();

    /** A moment a few minutes back that is still today (the tests ask for "today", and they must also pass just after midnight). */
    static LocalDateTime ago(long minutes) {
        LocalDateTime t = LocalDateTime.now().minusMinutes(minutes);
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay().plusSeconds(1);
        return t.isBefore(startOfToday) ? startOfToday : t;
    }
    static UUID siteId, otherId, signerId;
    static String siteKey, otherKey;
    static String locationId, marking1, marking2, shiftId, transferId, signatureId;
    static long outbox = 100;

    @org.springframework.test.context.DynamicPropertySource
    static void idp(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestIdp::issuer);
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    HttpHeaders admin() { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(TestIdp.admin()); return h; }

    HttpHeaders site(UUID id, String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Site-Id", id.toString());
        h.set("X-Site-Key", key);
        return h;
    }

    JsonNode call(HttpMethod m, String url, HttpHeaders h, Object body, int status) {
        ResponseEntity<String> r = rest.exchange(url, m, new HttpEntity<>(body, h), String.class);
        assertEquals(status, r.getStatusCode().value(), r.getBody());
        try { return r.getBody() == null || r.getBody().isBlank() ? null : mapper.readTree(r.getBody()); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    JsonNode get(String url) { return call(HttpMethod.GET, url, admin(), null, 200); }

    ObjectNode fixture(String name) {
        try (var in = getClass().getResourceAsStream("/fixtures/" + name + ".json")) { return (ObjectNode) mapper.readTree(in); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    Map<String, Object> event(String type, String entityId, JsonNode payload) {
        return Map.of("outboxId", ++outbox, "entityType", type, "entityId", entityId, "operation", "UPSERT", "payload", payload,
                "createdAt", LocalDateTime.now().withNano(0).toString());
    }

    ObjectNode marking(String uuid, String order, String truck, String fuel, String omc, String status, double litres, double rum, Double weighed) {
        ObjectNode m = fixture("TRUCK_MARKING");
        m.put("uuid", uuid).put("siteId", siteId.toString()).put("shiftId", shiftId).put("orderNumber", order).put("truckNumber", truck)
                .put("fuelType", fuel).put("oilMarketingCompany", omc).put("status", status).put("declaredVolume", litres)
                .put("doseCalculatedMl", rum).put("registeredTime", ago(10).toString());
        if (!"COMPLETED".equals(status)) m.putNull("completedTime");
        if (weighed != null) m.put("doseMeasuredG", weighed).put("doseSource", "SCALE");
        else { m.putNull("doseMeasuredG"); m.putNull("doseSource"); }
        return m;
    }

    @Test @Order(1)
    void pushRealPayloads() {
        siteId = UUID.fromString(call(HttpMethod.POST, "/api/admin/sites", admin(), Map.of("code", "RS" + N, "name", "Read Site " + N, "siteType", "FIXED"), 201).get("id").asText());
        otherId = UUID.fromString(call(HttpMethod.POST, "/api/admin/sites", admin(), Map.of("code", "RO" + N, "name", "Other Site " + N, "siteType", "WAREHOUSE"), 201).get("id").asText());
        siteKey = call(HttpMethod.POST, "/api/admin/sites/" + siteId + "/api-key", admin(), null, 200).get("apiKey").asText();
        otherKey = call(HttpMethod.POST, "/api/admin/sites/" + otherId + "/api-key", admin(), null, 200).get("apiKey").asText();
        signerId = UUID.fromString(call(HttpMethod.POST, "/api/admin/users", admin(),
                Map.of("username", "rs" + N, "fullName", "Sam Signer " + N, "role", "SUPERVISOR", "siteId", siteId), 201).get("id").asText());

        shiftId = UUID.randomUUID().toString();
        ObjectNode shift = fixture("SHIFT");
        shift.put("id", shiftId).put("siteId", siteId.toString()).put("supervisorId", signerId.toString()).put("startedAt", ago(60).toString());
        locationId = UUID.randomUUID().toString();
        shift.put("locationId", locationId).put("locationName", "Border post " + N);
        shift.put("status", "OPEN").putNull("endedAt").putNull("closingRumMl").putNull("varianceMl");

        marking1 = UUID.randomUUID().toString();
        marking2 = UUID.randomUUID().toString();
        ObjectNode m1 = marking(marking1, "RS-1-" + N, "AB-123", "DIESEL", "Alpha Oil", "COMPLETED", 10000, 100, 99.5);   // required 100 g, 99.5 g weighed
        m1.put("trailerNumber", "TL-9");
        ObjectNode m2 = marking(marking2, "RS-2-" + N, "ZZ-999", "PETROL", "Beta Fuels", "REGISTERED", 5000, 50, null);

        ObjectNode incident = fixture("INCIDENT");
        String containerId = UUID.randomUUID().toString();
        incident.put("id", UUID.randomUUID().toString()).put("siteId", siteId.toString()).put("shiftId", shiftId)
                .put("containerId", containerId).put("rumTypeCode", "RUM-A").put("reportedBy", signerId.toString()).put("occurredAt", ago(3).toString());
        ObjectNode container = fixture("CONTAINER");
        container.put("id", containerId).put("siteId", siteId.toString()).put("barcode", "RC-" + N).put("status", "OPENED");

        signatureId = UUID.randomUUID().toString();
        ObjectNode sig = fixture("SIGNATURE");
        sig.put("id", signatureId).put("entityType", "TRUCK_MARKING").put("entityId", marking1).put("step", "REGISTRATION")
                .put("signedBy", signerId.toString());

        transferId = UUID.randomUUID().toString();
        ObjectNode transfer = fixture("TRANSFER");
        ((ObjectNode) transfer.get("transfer")).put("id", transferId).put("fromSiteId", otherId.toString()).put("toSiteId", siteId.toString())
                .put("dispatchedAt", ago(30).toString());
        ArrayNode items = (ArrayNode) transfer.get("items");
        ((ObjectNode) items.get(0)).retain("barcode").put("received", true);       // taken in at the destination
        ((ObjectNode) items.get(1)).retain("barcode").put("received", false);      // still on the way

        JsonNode r = call(HttpMethod.POST, "/api/sync/push", site(siteId, siteKey), Map.of("events", List.of(
                event("SHIFT", shiftId, shift), event("TRUCK_MARKING", marking1, m1), event("TRUCK_MARKING", marking2, m2),
                event("CONTAINER", containerId, container), event("INCIDENT", incident.get("id").asText(), incident),
                event("SIGNATURE", signatureId, sig), event("TRANSFER", transferId, transfer))), 200);
        assertEquals(7, r.get("acked").size(), r.toString());
        assertEquals(0, r.get("rejected").size(), r.toString());
    }

    @Test @Order(2)
    void readApiRequiresAdminLogin() {
        call(HttpMethod.GET, "/api/web/overview", new HttpHeaders(), null, 401);
        call(HttpMethod.GET, "/api/web/overview", site(siteId, siteKey), null, 401); // a site key is not an admin login
        HttpHeaders wrong = new HttpHeaders(); wrong.setBearerAuth("not-a-jwt");
        for (String path : List.of("/api/web/overview", "/api/admin/sites")) {
            for (HttpHeaders h : List.of(new HttpHeaders(), wrong)) {
                ResponseEntity<String> r = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
                assertEquals(401, r.getStatusCode().value());
                String challenge = r.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
                assertTrue(challenge == null || !challenge.startsWith("Basic"),
                        "a Basic challenge makes browsers show a native login dialog over the web app");
            }
        }
    }

    @Test @Order(3)
    void overviewAndSiteStatus() {
        JsonNode o = get("/api/web/overview?siteId=" + siteId + "&from=" + TODAY + "&to=" + TODAY);
        assertEquals(2, o.get("trucks").asInt());
        assertEquals(1, o.get("completed").asInt());
        assertEquals(15000.0, o.get("fuelLitres").asDouble());
        assertEquals(149.5, o.get("rumMl").asDouble(), 1e-9, "the weighed dose, not the calculated one, is what counts");
        assertEquals(1, o.get("incidents").asInt());
        assertEquals(20.0, o.get("incidentMl").asDouble());
        assertEquals(1, o.get("openShifts").asInt());
        assertEquals(1, o.get("byDay").size());
        assertEquals(TODAY, o.get("byDay").get(0).get("day").asText());
        assertEquals(2, o.get("byDay").get(0).get("trucks").asInt());

        JsonNode quiet = get("/api/web/overview?siteId=" + otherId + "&from=" + TODAY + "&to=" + TODAY);
        assertEquals(0, quiet.get("trucks").asInt());

        JsonNode status = null;
        for (JsonNode s : get("/api/web/sites/status")) if (s.get("id").asText().equals(siteId.toString())) status = s;
        assertNotNull(status);
        assertFalse(status.get("lastSyncAt").isNull());
        assertTrue(status.get("lastSyncAgeSeconds").asLong() >= 0 && status.get("lastSyncAgeSeconds").asLong() < 120);
        for (JsonNode s : get("/api/web/sites/status")) if (s.get("id").asText().equals(otherId.toString()) && s.get("lastSyncAgeSeconds").isNull()) { /* never synced */ }
        assertTrue(status.get("shiftOpen").asBoolean());
        assertEquals(2, status.get("trucksToday").asInt());
    }

    @Test @Order(4)
    void markingsFilterPageAndDetail() {
        String base = "/api/web/markings?siteId=" + siteId;
        assertEquals(2, get(base).get("total").asInt());
        assertEquals(1, get(base + "&fuel=DIESEL").get("total").asInt());
        assertEquals(1, get(base + "&status=REGISTERED").get("total").asInt());
        assertEquals(1, get(base + "&q=zz-9").get("items").size());
        assertEquals(0, get(base + "&q=nomatch").get("total").asInt());
        assertEquals(0, get(base + "&from=2020-01-01&to=2020-01-31").get("total").asInt());
        JsonNode paged = get(base + "&size=1&page=1");
        assertEquals(2, paged.get("total").asInt());
        assertEquals(1, paged.get("items").size());
        assertEquals(200, get(base + "&size=100000").get("size").asInt(), "page size is capped");
        assertEquals(marking2, get(base).get("items").get(0).get("id").asText(), "newest first? both registered 10 min ago; order is stable per timestamp");

        // text search treats % and ' as plain characters (no wildcard match-all, no SQL error)
        assertEquals(0, get(base + "&q=%25").get("total").asInt());
        assertEquals(0, get(base + "&q=" + java.net.URLEncoder.encode("x'; DROP TABLE synced_entities; --", java.nio.charset.StandardCharsets.UTF_8)).get("total").asInt());

        JsonNode d = get("/api/web/markings/" + marking1);
        assertEquals("AB-123", d.get("marking").get("truckNumber").asText());
        assertEquals("TL-9", d.get("marking").get("trailerNumber").asText(), "trailer / wagon number is shown");
        assertEquals(1, get(base + "&q=TL-9").get("total").asInt(), "and searchable");
        assertEquals("Alpha Oil", d.get("marking").get("omc").asText());
        assertEquals(2, d.get("compartments").size());
        assertEquals("C1", d.get("compartments").get(0).get("number").asText());
        assertEquals(100.0, d.get("doseRequiredG").asDouble(), 1e-9);
        assertEquals(99.5, d.get("doseMeasuredG").asDouble(), 1e-9);
        assertEquals("SCALE", d.get("doseSource").asText());
        JsonNode notDosed = get("/api/web/markings/" + marking2);
        assertTrue(notDosed.get("doseMeasuredG").isNull() && notDosed.get("doseSource").isNull(), "an undosed truck has no measured dose");
        assertEquals(1, d.get("signatures").size());
        assertEquals("Sam Signer " + N, d.get("signatures").get(0).get("signedBy").asText());
        call(HttpMethod.GET, "/api/web/markings/" + UUID.randomUUID(), admin(), null, 404);
    }

    @Test @Order(5)
    void signatureImageIsServedAsPng() {
        ResponseEntity<byte[]> r = rest.exchange("/api/web/signatures/" + signatureId + "/image", HttpMethod.GET, new HttpEntity<>(admin()), byte[].class);
        assertEquals(200, r.getStatusCode().value());
        assertEquals(MediaType.IMAGE_PNG, r.getHeaders().getContentType());
        assertArrayEquals(new byte[]{1, 2, 3}, r.getBody());
        call(HttpMethod.GET, "/api/web/signatures/" + UUID.randomUUID() + "/image", admin(), null, 404);
    }

    @Test @Order(6)
    void summariesGroupCorrectly() {
        String q = "&siteId=" + siteId + "&from=" + TODAY + "&to=" + TODAY;
        JsonNode byFuel = get("/api/web/markings/summary?groupBy=fuel" + q);
        assertEquals(2, byFuel.size());
        assertEquals("DIESEL", byFuel.get(0).get("label").asText());
        assertEquals(99.5, byFuel.get(0).get("rumMl").asDouble(), 1e-9);
        assertEquals(2, get("/api/web/markings/summary?groupBy=omc" + q).size());
        assertEquals(1, get("/api/web/markings/summary?groupBy=day" + q).size());
        assertEquals("Read Site " + N, get("/api/web/markings/summary?groupBy=site" + q).get(0).get("label").asText());
        call(HttpMethod.GET, "/api/web/markings/summary?groupBy=password" + q, admin(), null, 400);
        call(HttpMethod.GET, "/api/web/markings/summary?groupBy=" + java.net.URLEncoder.encode("fuel; DROP TABLE x", java.nio.charset.StandardCharsets.UTF_8), admin(), null, 400);
    }

    @Test @Order(7)
    void inventoryIncidentsShiftsAndTransfers() {
        JsonNode inv = get("/api/web/inventory?siteId=" + siteId);
        assertEquals(1, inv.size());
        assertEquals("OPENED", inv.get(0).get("status").asText());
        assertEquals(880.0, inv.get(0).get("volumeMl").asDouble());
        assertEquals(1, get("/api/web/containers?siteId=" + siteId + "&q=rc-").get("total").asInt());
        JsonNode jar = get("/api/web/containers?siteId=" + siteId + "&q=rc-").get("items").get(0);
        assertEquals("TS1", jar.get("topSeal").asText(), "the seal numbers are shown");
        assertEquals("BS1", jar.get("bottomSeal").asText());
        assertEquals(1, get("/api/web/containers?siteId=" + siteId + "&q=bs1").get("total").asInt(), "and can be searched");
        assertEquals(0, get("/api/web/containers?siteId=" + siteId + "&q=nosuchseal").get("total").asInt());
        assertEquals(0, get("/api/web/containers?siteId=" + siteId + "&status=SEALED").get("total").asInt());

        JsonNode inc = get("/api/web/incidents?siteId=" + siteId);
        assertEquals(1, inc.get("total").asInt());
        assertEquals("SPILLAGE", inc.get("items").get(0).get("type").asText());
        assertEquals("RC-" + N, inc.get("items").get(0).get("container").asText());
        assertEquals("Sam Signer " + N, inc.get("items").get(0).get("reportedBy").asText());
        assertEquals(0, get("/api/web/incidents?siteId=" + siteId + "&type=LEAKAGE").get("total").asInt());
        assertEquals("RUM-A", inc.get("items").get(0).get("rumType").asText(), "the incident says which rum was lost");
        assertEquals(1, get("/api/web/incidents?siteId=" + siteId + "&rumType=RUM-A").get("total").asInt(), "and can be filtered by it");
        assertEquals(0, get("/api/web/incidents?siteId=" + siteId + "&rumType=OTHER-RUM").get("total").asInt());

        JsonNode sh = get("/api/web/shifts?siteId=" + siteId + "&status=OPEN");
        assertEquals(1, sh.get("total").asInt());
        assertEquals("Sam Signer " + N, sh.get("items").get(0).get("supervisor").asText());
        assertTrue(sh.get("items").get(0).get("closingMl").isNull());

        JsonNode tr = get("/api/web/transfers?siteId=" + siteId);
        assertEquals(1, tr.get("total").asInt());
        assertEquals(1, get("/api/web/transfers?siteId=" + otherId).get("total").asInt(), "visible from both ends");
        assertEquals(2, tr.get("items").get(0).get("items").asInt());
        assertEquals(1, tr.get("items").get(0).get("received").asInt(), "one of the two containers has arrived");
        JsonNode td = get("/api/web/transfers/" + transferId);
        assertEquals("Other Site " + N, td.get("transfer").get("fromSite").asText());
        assertEquals("SUPPLY", td.get("transfer").get("kind").asText(), "transfers from before returns existed are supplies");
        assertEquals(1, StreamSupport(td.get("items")).filter(i -> i.get("received").asBoolean()).count());
        assertEquals(2, td.get("items").size());
        call(HttpMethod.GET, "/api/web/transfers/" + UUID.randomUUID(), admin(), null, 404);
    }

    static java.util.stream.Stream<JsonNode> StreamSupport(JsonNode arr) {
        return java.util.stream.StreamSupport.stream(arr.spliterator(), false);
    }

    @Test @Order(8)
    void aDoseSourceThatTheDesktopNeverWritesIsRefusedAtTheDoor() {
        ObjectNode good = marking(UUID.randomUUID().toString(), "RS-9-" + N, "ZZ-111", "DIESEL", "Alpha Oil", "DOSED", 1000, 10, 10.0);
        ObjectNode badSource = good.deepCopy();
        String badId = UUID.randomUUID().toString();
        badSource.put("uuid", badId).put("doseSource", "GUESS");
        ObjectNode badNumber = good.deepCopy();
        String badNumberId = UUID.randomUUID().toString();
        badNumber.put("uuid", badNumberId).put("doseMeasuredG", "lots");
        ObjectNode ok = good.deepCopy();
        String okId = UUID.randomUUID().toString();
        ok.put("uuid", okId);
        JsonNode r = call(HttpMethod.POST, "/api/sync/push", site(siteId, siteKey), Map.of("events", List.of(
                event("TRUCK_MARKING", badId, badSource), event("TRUCK_MARKING", badNumberId, badNumber), event("TRUCK_MARKING", okId, ok))), 200);
        assertEquals(1, r.get("acked").size(), r.toString());
        assertEquals(2, r.get("rejected").size(), r.toString());
        assertTrue(r.get("rejected").toString().contains("Bad doseSource") && r.get("rejected").toString().contains("Bad doseMeasuredG"), r.toString());
        get("/api/web/overview?siteId=" + siteId);   // the typed views still work after the rejected pushes
    }

    ObjectNode stockCount(String id, UUID site, double system, double counted) {
        ObjectNode c = mapper.createObjectNode();
        c.put("id", id).put("siteId", site.toString()).put("countedBy", signerId.toString()).put("countedAt", ago(5).withNano(0).toString())
                .put("comment", "month end").put("totalSystemG", system).put("totalCountedG", counted).put("totalVarianceG", counted - system);
        var lines = c.putArray("lines");
        lines.addObject().put("barcode", "IBC-A-" + N).put("containerType", "IBC").put("rumTypeCode", "STANDARD")
                .put("systemG", 600_000.0).put("countedG", 600_000.0).put("varianceG", 0.0).put("source", "SCALE");
        lines.addObject().put("barcode", "IBC-B-" + N).put("containerType", "DRUM").put("rumTypeCode", "STANDARD")
                .put("systemG", system - 600_000.0).put("countedG", counted - 600_000.0).put("varianceG", counted - system).put("source", "MANUAL");
        return c;
    }

    @Test @Order(9)
    void warehouseStockCountsAreAcceptedAndReadBack() {
        String id = UUID.randomUUID().toString();
        ObjectNode count = stockCount(id, siteId, 900_000, 899_500);
        ObjectNode otherSites = stockCount(UUID.randomUUID().toString(), otherId, 1, 1);   // claims to belong to another site
        ObjectNode noLines = stockCount(UUID.randomUUID().toString(), siteId, 1, 1);
        noLines.putArray("lines");
        ObjectNode badNumber = stockCount(UUID.randomUUID().toString(), siteId, 1, 1);
        badNumber.put("totalCountedG", "lots");
        JsonNode r = call(HttpMethod.POST, "/api/sync/push", site(siteId, siteKey), Map.of("events", List.of(
                event("STOCK_COUNT", id, count), event("STOCK_COUNT", otherSites.get("id").asText(), otherSites),
                event("STOCK_COUNT", noLines.get("id").asText(), noLines), event("STOCK_COUNT", badNumber.get("id").asText(), badNumber))), 200);
        assertEquals(1, r.get("acked").size(), r.toString());
        assertEquals(3, r.get("rejected").size(), r.toString());
        assertTrue(r.get("rejected").toString().contains("belongs to another site"), r.toString());

        JsonNode list = get("/api/web/stock-counts?siteId=" + siteId);
        assertEquals(1, list.get("total").asInt());
        JsonNode row = list.get("items").get(0);
        assertEquals("Sam Signer " + N, row.get("countedBy").asText());
        assertEquals("Read Site " + N, row.get("siteName").asText());
        assertEquals(900_000, row.get("totalSystemG").asDouble(), 0.001);
        assertEquals(899_500, row.get("totalCountedG").asDouble(), 0.001);
        assertEquals(-500, row.get("totalVarianceG").asDouble(), 0.001);
        assertEquals(2, row.get("containers").asInt());
        assertEquals(1, row.get("differing").asInt(), "only the drum differs");
        assertEquals("month end", row.get("comment").asText());
        assertEquals(0, get("/api/web/stock-counts?siteId=" + otherId).get("total").asInt());
        assertEquals(1, get("/api/web/stock-counts?siteId=" + siteId + "&from=" + TODAY + "&to=" + TODAY).get("total").asInt());

        JsonNode detail = get("/api/web/stock-counts/" + id);
        assertEquals(2, detail.get("lines").size());
        assertEquals("IBC-A-" + N, detail.get("lines").get(0).get("barcode").asText());
        assertEquals("MANUAL", detail.get("lines").get(1).get("source").asText());
        assertEquals("WAREHOUSE", list.get("items").get(0).get("kind").asText(), "counts from before reconciliations existed are warehouse counts");
        assertEquals(-500, detail.get("lines").get(1).get("varianceG").asDouble(), 0.001);
        call(HttpMethod.GET, "/api/web/stock-counts/" + UUID.randomUUID(), admin(), null, 404);

        // viewers read counts; nobody without a login does
        HttpHeaders viewer = new HttpHeaders();
        viewer.setBearerAuth(TestIdp.viewer());
        call(HttpMethod.GET, "/api/web/stock-counts", viewer, null, 200);
        call(HttpMethod.GET, "/api/web/stock-counts", new HttpHeaders(), null, 401);
    }

    @Test @Order(10)
    void shiftReconciliationsCarryTheirCausesAndCanBeToldApartFromWarehouseCounts() {
        String id = UUID.randomUUID().toString();
        ObjectNode rec = mapper.createObjectNode();
        rec.put("id", id).put("siteId", siteId.toString()).put("countedBy", signerId.toString()).put("countedAt", ago(1).withNano(0).toString())
                .put("kind", "SHIFT").put("shiftId", shiftId).put("totalSystemG", 30_000.0).put("totalCountedG", 29_400.0).put("totalVarianceG", -600.0);
        var lines = rec.putArray("lines");
        lines.addObject().put("barcode", "J-A-" + N).put("containerType", "JERRICAN_PLASTIC").put("rumTypeCode", "STANDARD")
                .put("systemG", 20_000.0).put("countedG", 19_400.0).put("varianceG", -600.0).put("source", "SCALE")
                .put("cause", "LEAKAGE").put("note", "cap was loose");
        lines.addObject().put("barcode", "J-B-" + N).put("containerType", "JERRICAN_PLASTIC").put("rumTypeCode", "STANDARD")
                .put("systemG", 10_000.0).put("countedG", 10_000.0).put("varianceG", 0.0).put("source", "MANUAL");
        lines.addObject().put("barcode", "J-S-" + N).put("containerType", "JERRICAN_PLASTIC").put("rumTypeCode", "STANDARD")
                .put("systemG", 22_000.0).put("countedG", 0.0).put("varianceG", -22_000.0).put("source", "MANUAL").put("cause", "OTHER")
                .put("note", "not in the store room").put("check", "MISSING");
        assertEquals(1, call(HttpMethod.POST, "/api/sync/push", site(siteId, siteKey), Map.of("events", List.of(event("STOCK_COUNT", id, rec))), 200).get("acked").size());

        assertEquals(1, get("/api/web/stock-counts?siteId=" + siteId + "&kind=SHIFT").get("total").asInt());
        assertEquals(1, get("/api/web/stock-counts?siteId=" + siteId + "&kind=WAREHOUSE").get("total").asInt());
        assertEquals(2, get("/api/web/stock-counts?siteId=" + siteId).get("total").asInt());
        JsonNode row = get("/api/web/stock-counts?siteId=" + siteId + "&kind=SHIFT").get("items").get(0);
        assertEquals("SHIFT", row.get("kind").asText());
        assertEquals(2, row.get("differing").asInt(), "the weighed jerrican that is short and the sealed one that was not found");
        JsonNode detail = get("/api/web/stock-counts/" + id);
        assertEquals("LEAKAGE", detail.get("lines").get(0).get("cause").asText());
        assertEquals("cap was loose", detail.get("lines").get(0).get("note").asText());
        assertTrue(detail.get("lines").get(1).get("cause").isNull());
        assertTrue(detail.get("lines").get(1).get("check").isNull(), "a weighed jerrican has no seal check");
        assertEquals("MISSING", detail.get("lines").get(2).get("check").asText(), "a sealed jerrican that was not found says so");
        assertEquals("not in the store room", detail.get("lines").get(2).get("note").asText());
    }

    @Test @Order(11)
    void aTransferOfEmptiesBackToTheWarehouseIsToldApartFromSupplies() {
        String id = UUID.randomUUID().toString();
        ObjectNode back = fixture("TRANSFER");
        ((ObjectNode) back.get("transfer")).put("id", id).put("fromSiteId", siteId.toString()).put("toSiteId", otherId.toString())
                .put("status", "DISPATCHED").put("kind", "RETURN").put("dispatchedAt", ago(2).toString());
        ArrayNode items = (ArrayNode) back.get("items");
        items.removeAll();
        items.addObject().put("barcode", "EMPTY-1-" + N).put("received", false);
        assertEquals(1, call(HttpMethod.POST, "/api/sync/push", site(siteId, siteKey), Map.of("events", List.of(event("TRANSFER", id, back))), 200).get("acked").size());
        JsonNode td = get("/api/web/transfers/" + id);
        assertEquals("RETURN", td.get("transfer").get("kind").asText());
        assertEquals(1, td.get("items").size());
        assertEquals(0, td.get("transfer").get("received").asInt());
        JsonNode list = get("/api/web/transfers?siteId=" + siteId);
        boolean found = false;
        for (JsonNode t : list.get("items")) if (t.get("id").asText().equals(id)) found = "RETURN".equals(t.get("kind").asText());
        assertTrue(found, "the list says it is a return");
    }

    @Test @Order(13)
    void aDeletedMarkingCountsNowhereButCanStillBeLookedUp() {
        String q = "&siteId=" + siteId + "&from=" + TODAY + "&to=" + TODAY;
        JsonNode ov0 = get("/api/web/overview?siteId=" + siteId + "&from=" + TODAY + "&to=" + TODAY);
        int total0 = get("/api/web/markings?siteId=" + siteId).get("total").asInt();
        double[] diesel0 = figures(get("/api/web/markings/summary?groupBy=fuel" + q), "DIESEL");
        String id = UUID.randomUUID().toString();
        ObjectNode m = marking(id, "RS-DEL-" + N, "DL-1", "DIESEL", "Alpha Oil", "DELETED", 3000, 30, null);
        m.put("deletedTime", ago(1).toString());
        assertEquals(1, call(HttpMethod.POST, "/api/sync/push", site(siteId, siteKey), Map.of("events", List.of(event("TRUCK_MARKING", id, m))), 200).get("acked").size());

        // not counted in the overview, the summaries, the list or the site's trucks today
        JsonNode ov1 = get("/api/web/overview?siteId=" + siteId + "&from=" + TODAY + "&to=" + TODAY);
        assertEquals(ov0.get("trucks").asInt(), ov1.get("trucks").asInt());
        assertEquals(ov0.get("fuelLitres").asDouble(), ov1.get("fuelLitres").asDouble(), 1e-9);
        assertEquals(total0, get("/api/web/markings?siteId=" + siteId).get("total").asInt(), "the list leaves it out");
        assertEquals(diesel0[0], figures(get("/api/web/markings/summary?groupBy=fuel" + q), "DIESEL")[0], 1e-9);
        assertEquals(0, get("/api/web/markings?siteId=" + siteId + "&q=DL-1").get("total").asInt());
        // ... but it can be asked for, and opened
        JsonNode deleted = get("/api/web/markings?siteId=" + siteId + "&status=DELETED");
        assertEquals(1, deleted.get("total").asInt());
        assertEquals("DL-1", deleted.get("items").get(0).get("truckNumber").asText());
        assertEquals("DELETED", get("/api/web/markings/" + id).get("marking").get("status").asText());
    }

    /** {trucks, litres, rum} of the summary row for a fuel, or zeros when there is none. */
    static double[] figures(JsonNode summary, String fuel) {
        for (JsonNode r : summary) if (fuel.equals(r.get("label").asText())) return new double[]{r.get("trucks").asDouble(), r.get("fuelLitres").asDouble(), r.get("rumMl").asDouble()};
        return new double[3];
    }

    @Test @Order(12)
    void aTruckWithTwoFuelsCountsUnderBothAndShowsItsCompartmentsAndDoses() {
        String q = "&siteId=" + siteId + "&from=" + TODAY + "&to=" + TODAY;
        JsonNode before = get("/api/web/markings/summary?groupBy=fuel" + q);
        double[] d0 = figures(before, "DIESEL"), p0 = figures(before, "PETROL");
        int diesel0 = get("/api/web/markings?siteId=" + siteId + "&fuel=DIESEL").get("total").asInt(), petrol0 = get("/api/web/markings?siteId=" + siteId + "&fuel=PETROL").get("total").asInt();
        String id = UUID.randomUUID().toString();
        ObjectNode m = marking(id, "RS-MIX-" + N, "MX-1", "DIESEL+PETROL", "Alpha Oil", "COMPLETED", 10000, 100, 100.5);
        m.put("rumType", "RA+RB").putNull("trailerNumber");
        var comps = m.putArray("compartments");
        comps.addObject().put("compartmentNumber", "C1").put("capacity", 6000.0).put("actualVolume", 6000.0).put("fuelType", "DIESEL").put("rumType", "RA");
        comps.addObject().put("compartmentNumber", "C2").put("capacity", 4000.0).put("actualVolume", 4000.0).put("fuelType", "PETROL").put("rumType", "RB");
        var doses = m.putArray("doses");
        doses.addObject().put("compartment", "C1").put("rumType", "RA").put("requiredG", 60.0).put("measuredG", 60.5).put("source", "MANUAL").put("dosedAt", ago(8).toString());
        doses.addObject().put("compartment", "C2").put("rumType", "RB").put("requiredG", 40.0).put("measuredG", 40.0).put("source", "SCALE").put("dosedAt", ago(7).toString());
        var breakdown = m.putArray("fuelBreakdown");
        breakdown.addObject().put("fuelType", "DIESEL").put("rumType", "RA").put("litres", 6000.0).put("rumG", 60.5);
        breakdown.addObject().put("fuelType", "PETROL").put("rumType", "RB").put("litres", 4000.0).put("rumG", 40.0);
        assertEquals(1, call(HttpMethod.POST, "/api/sync/push", site(siteId, siteKey), Map.of("events", List.of(event("TRUCK_MARKING", id, m))), 200).get("acked").size());

        // under both fuels, each with its own litres and the rum dosed for it (older trucks without a breakdown count as one fuel)
        JsonNode after = get("/api/web/markings/summary?groupBy=fuel" + q);
        double[] d1 = figures(after, "DIESEL"), p1 = figures(after, "PETROL");
        assertEquals(1, d1[0] - d0[0], 1e-9, "one more truck under diesel");
        assertEquals(6_000, d1[1] - d0[1], 1e-6, "only the diesel compartment's litres");
        assertEquals(60.5, d1[2] - d0[2], 1e-6, "and the rum dosed for the diesel");
        assertEquals(1, p1[0] - p0[0], 1e-9, "and one more under petrol");
        assertEquals(4_000, p1[1] - p0[1], 1e-6);
        assertEquals(40.0, p1[2] - p0[2], 1e-6);

        // the fuel filter finds a truck by any of its compartments' fuels
        assertEquals(diesel0 + 1, get("/api/web/markings?siteId=" + siteId + "&fuel=DIESEL").get("total").asInt());
        assertEquals(petrol0 + 1, get("/api/web/markings?siteId=" + siteId + "&fuel=PETROL").get("total").asInt());
        assertEquals(0, get("/api/web/markings?siteId=" + siteId + "&fuel=KEROSENE").get("total").asInt());
        JsonNode row = get("/api/web/markings?siteId=" + siteId + "&q=MX-1").get("items").get(0);
        assertEquals("DIESEL+PETROL", row.get("fuelType").asText());
        assertEquals("RA+RB", row.get("rumType").asText());

        // the detail has each compartment's fuel and rum type, and the doses
        JsonNode d = get("/api/web/markings/" + id);
        assertEquals("DIESEL", d.get("compartments").get(0).get("fuelType").asText());
        assertEquals("RA", d.get("compartments").get(0).get("rumType").asText());
        assertEquals("PETROL", d.get("compartments").get(1).get("fuelType").asText());
        assertEquals(2, d.get("doses").size());
        assertEquals("C1", d.get("doses").get(0).get("compartment").asText(), "each dose is for a compartment");
        assertEquals("RA", d.get("doses").get(0).get("rumType").asText());
        assertEquals(60.5, d.get("doses").get(0).get("measuredG").asDouble(), 1e-9);
        assertEquals("MANUAL", d.get("doses").get(0).get("source").asText());
        // an older marking has no doses and compartments without fuels
        JsonNode old = get("/api/web/markings/" + marking1);
        assertEquals(0, old.get("doses").size());
        assertTrue(old.get("compartments").get(0).get("fuelType").isNull());
    }

    @Test @Order(14)
    void headOfficeSeesWhereAShiftAndItsMarkingsHappened() {
        JsonNode sh = get("/api/web/shifts?siteId=" + siteId + "&locationId=" + locationId);
        assertEquals(1, sh.get("total").asInt());
        assertEquals("Border post " + N, sh.get("items").get(0).get("location").asText());
        assertEquals(0, get("/api/web/shifts?siteId=" + siteId + "&locationId=" + UUID.randomUUID()).get("total").asInt());

        JsonNode mk = get("/api/web/markings?siteId=" + siteId + "&locationId=" + locationId);
        assertTrue(mk.get("total").asInt() >= 2, "the trucks marked during the shift are at that location");
        assertEquals("Border post " + N, mk.get("items").get(0).get("location").asText());
        assertEquals("Border post " + N, get("/api/web/markings/" + marking1).get("marking").get("location").asText());
        assertEquals(0, get("/api/web/markings?siteId=" + siteId + "&locationId=" + UUID.randomUUID()).get("total").asInt());

        boolean found = false;
        for (JsonNode s : get("/api/web/sites/status")) if (s.get("id").asText().equals(siteId.toString())) { found = true; assertEquals("Border post " + N, s.get("location").asText()); }
        assertTrue(found, "the sites overview says where the site last worked");
    }
}
