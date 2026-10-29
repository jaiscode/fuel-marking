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
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Many sites dispatching at once against a small connection pool: a push must never need a second connection while it
 * still holds its first, or the pushes starve each other and everything else (pulls, the web app) waits on the pool.
 */
@org.springframework.test.context.TestPropertySource(locations = "classpath:it-database.properties")   // the shared test database settings
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=3000",
        "fuelmarking.master-data-drain-enabled=false",
        "fuelmarking.sync-sequencer-enabled=false",
        "fuelmarking.auth.audience=fuelmarking-api"})
@EmbeddedKafka(partitions = 1)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS) // its own small pool
class PushConcurrencyIT {
    static final String N = UUID.randomUUID().toString().substring(0, 6);
    static final int POOL = 5, PUSHES = 8;
    static final long GATE = 727_999L;   // advisory lock the test holds to line pushes up

    @org.springframework.test.context.DynamicPropertySource
    static void idp(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestIdp::issuer);
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.boot.autoconfigure.jdbc.DataSourceProperties db;   // the gate connects with the test's own settings

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

    Map<String, Object> event(long outboxId, String type, String id, Map<String, Object> payload) {
        return Map.of("outboxId", outboxId, "entityType", type, "entityId", id, "operation", "UPSERT", "payload", payload, "createdAt", "2026-10-14T10:00:00");
    }

    static long waitingAtGate(java.sql.Connection gate) throws java.sql.SQLException {
        try (var st = gate.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = " + GATE + " AND NOT granted")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void concurrentDispatchPushesAreAllAckedChecksAreRecordedAndPullsStayResponsive() throws Exception {
        UUID holder = newSite("PH"), sender = newSite("PS"), receiver = newSite("PR");
        String holderKey = apiKey(holder), senderKey = apiKey(sender), receiverKey = apiKey(receiver);

        // every container is held by another site, so every push raises one conflict and needs its check to run
        List<Map<String, Object>> containers = new ArrayList<>();
        for (int i = 0; i < PUSHES; i++) {
            String cid = UUID.randomUUID().toString();
            containers.add(event(i + 1, "CONTAINER", cid, Map.of("id", cid, "barcode", "PC" + i + "-" + N, "siteId", holder.toString(),
                    "status", "FILLED", "type", "JERRICAN_PLASTIC", "capacityMl", 1000.0, "currentVolumeMl", 1000)));
        }
        call(HttpMethod.POST, "/api/sync/push", site(holder, holderKey), Map.of("events", containers), 200);

        // Line the pushes up deterministically: each blocks inside its write (holding its pool connection) until the test lets
        // go, so all POOL connections are held by pushes at once - the moment a push needing a second connection starves.
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION push_concurrency_it_gate() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_advisory_lock_shared(%d); PERFORM pg_advisory_unlock_shared(%d); RETURN NEW; END $$""".formatted(GATE, GATE));
        jdbc.execute("CREATE TRIGGER push_concurrency_it_gate BEFORE INSERT ON synced_entities FOR EACH ROW "
                + "WHEN (NEW.entity_type = 'TRANSFER' AND NEW.payload -> 'items' -> 0 ->> 'barcode' LIKE 'PC%-" + N + "') "
                + "EXECUTE FUNCTION push_concurrency_it_gate()");
        ExecutorService pool = Executors.newFixedThreadPool(PUSHES + 1);
        CountDownLatch go = new CountDownLatch(1);
        // the gate is held on a connection of its own, outside the application's pool
        try (java.sql.Connection gate = java.sql.DriverManager.getConnection(db.determineUrl(), db.determineUsername(), db.determinePassword())) {
            try (var st = gate.createStatement()) { st.execute("SELECT pg_advisory_lock(" + GATE + ")"); }
            List<Future<JsonNode>> pushes = new ArrayList<>();
            for (int i = 0; i < PUSHES; i++) {
                int n = i;
                pushes.add(pool.submit(() -> {
                    go.await();
                    String t = UUID.randomUUID().toString();
                    Map<String, Object> transfer = Map.of("transfer", Map.of("id", t, "fromSiteId", sender.toString(), "toSiteId", receiver.toString(),
                            "status", "DISPATCHED", "dispatchedAt", "2026-10-14T10:00:00"), "items", List.of(Map.of("barcode", "PC" + n + "-" + N, "received", false)));
                    return call(HttpMethod.POST, "/api/sync/push", site(sender, senderKey), Map.of("events", List.of(event(100 + n, "TRANSFER", t, transfer))), 200);
                }));
            }
            go.countDown();
            long end = System.currentTimeMillis() + 10_000;
            while (waitingAtGate(gate) < POOL) {
                assertTrue(System.currentTimeMillis() < end, "pushes did not line up at the gate");
                Thread.sleep(20);
            }
            try (var st = gate.createStatement()) { st.execute("SELECT pg_advisory_unlock(" + GATE + ")"); }
            Thread.sleep(100);   // the released pushes commit and reach their custody check
            long started = System.nanoTime();
            call(HttpMethod.GET, "/api/sync/pull?cursor=0", site(receiver, receiverKey), null, 200);
            long pullMs = (System.nanoTime() - started) / 1_000_000;

            for (Future<JsonNode> f : pushes) assertEquals(1, f.get(30, TimeUnit.SECONDS).get("acked").size());
            assertTrue(pullMs < 2000, "a pull waited " + pullMs + " ms for a connection while pushes were running");
        } finally {
            pool.shutdownNow();
            jdbc.execute("DROP TRIGGER IF EXISTS push_concurrency_it_gate ON synced_entities");
            jdbc.execute("DROP FUNCTION IF EXISTS push_concurrency_it_gate()");
        }
        long flagged = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'SYNC_CONFLICT' AND target LIKE ?", Long.class, "PC%-" + N);
        assertEquals(PUSHES, flagged, "every push's custody check ran (none starved of a connection)");
    }
}
