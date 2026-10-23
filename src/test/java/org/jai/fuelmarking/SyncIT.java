package org.jai.fuelmarking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.*;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.service.*;
import org.jai.fuelmarking.sync.*;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SyncIT {
    static final ObjectMapper M = new ObjectMapper();
    static final byte[] SIG = {1, 2, 3};
    static final String N = UUID.randomUUID().toString().substring(0, 6);
    static final Site wh = new Site(UUID.randomUUID(), "SW" + N, "Warehouse " + N, "WAREHOUSE");
    static final Site dest = new Site(UUID.randomUUID(), "SD" + N, "Dest " + N, "FIXED");
    static final UUID TRANSFER_ID = UUID.randomUUID();

    static HttpServer server;
    static String baseUrl;
    static final List<JsonNode> pushes = new ArrayList<>();
    static final List<String> pullQueries = new ArrayList<>();
    static final AtomicReference<String> pullBody = new AtomicReference<>("{\"transfers\":[],\"containers\":[],\"cursor\":0}");
    static final AtomicInteger forcedStatus = new AtomicInteger(200);
    static AppUser whMgr, supervisor;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/sync/push", ex -> {
            JsonNode body = M.readTree(ex.getRequestBody());
            pushes.add(body);
            ObjectNode resp = M.createObjectNode();
            ArrayNode acked = resp.putArray("acked"), rejected = resp.putArray("rejected");
            for (JsonNode e : body.path("events")) {
                if ("SIGNATURE".equals(e.path("entityType").asText()))
                    rejected.addObject().put("outboxId", e.path("outboxId").asLong()).put("reason", "test rejects signatures");
                else acked.add(e.path("outboxId").asLong());
            }
            reply(ex, forcedStatus.get(), M.writeValueAsString(resp));
        });
        server.createContext("/api/sync/pull", ex -> {
            pullQueries.add(ex.getRequestURI().getQuery());
            reply(ex, forcedStatus.get(), pullBody.get());
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        Tx.run(c -> {
            MasterDataDao m = new MasterDataDao();
            m.upsertSite(c, wh); m.upsertSite(c, dest);
            whMgr = insertUser(c, "swh", Role.WAREHOUSE_MANAGER, wh);
            supervisor = insertUser(c, "ssup", Role.SUPERVISOR, dest);
            return null;
        });
    }

    @AfterAll
    static void stop() { server.stop(0); }

    static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static AppUser insertUser(java.sql.Connection c, String name, Role r, Site s) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), name + N, name, r, s.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, u.username()); ps.setString(3, name); ps.setString(4, r.name()); ps.setObject(5, s.id());
            ps.executeUpdate();
        }
        return u;
    }

    static long count(String sql, Object... args) {
        return Tx.call(c -> {
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
            }
        });
    }

    SyncAgent agent(String url, String key) { return new SyncAgent(url, wh.id(), key); }

    @Test @Order(1)
    void pushDrainsOutboxAcksAndParksRejections() {
        Fixtures.ibc(new ContainerService(), whMgr, wh, "SSRC1-" + N, 900_000);
        Fixtures.plastic(new ContainerService(), whMgr, wh, "SJ1-" + N, 1000, 1000, "SSRC1-" + N);
        new ShiftService(); // no-op: constructing must not write
        assertTrue(count("SELECT count(*) FROM sync_outbox WHERE synced_at IS NULL") > 0);

        assertTrue(agent(baseUrl, "key").syncOnce());
        assertEquals(0, count("SELECT count(*) FROM sync_outbox WHERE synced_at IS NULL"));
        assertFalse(pushes.isEmpty());
        JsonNode first = pushes.get(0).path("events").get(0);
        assertTrue(first.has("outboxId") && first.has("payload") && first.path("createdAt").asText().contains("T"));
        // the container we just created went out with the owning site in its payload
        boolean found = false;
        for (JsonNode push : pushes) for (JsonNode e : push.path("events"))
            if ("CONTAINER".equals(e.path("entityType").asText()) && ("SJ1-" + N).equals(e.path("payload").path("barcode").asText())) {
                assertEquals(wh.id().toString(), e.path("payload").path("siteId").asText());
                found = true;
            }
        assertTrue(found);
    }

    @Test @Order(2)
    void rejectedEventsAreParkedWithAReasonAndNotRetried() {
        long before = pushes.size();
        Tx.run(c -> {
            try (var ps = c.prepareStatement("INSERT INTO sync_outbox(entity_type, entity_id, operation, payload) VALUES ('SIGNATURE','sig-" + N + "','UPSERT','{}'::jsonb)")) {
                ps.executeUpdate();
            }
            return null;
        });
        assertTrue(agent(baseUrl, "key").syncOnce());
        assertEquals(1, count("SELECT count(*) FROM sync_outbox WHERE entity_id = ? AND synced_at IS NOT NULL AND last_error = 'test rejects signatures'", "sig-" + N));
        assertTrue(agent(baseUrl, "key").syncOnce());
        assertEquals(before + 1, pushes.size(), "a parked event must not be pushed again");
    }

    @Test @Order(3)
    void unreachableOrUnauthorisedCentralLeavesOutboxIntactForRetry() {
        Fixtures.ibc(new ContainerService(), whMgr, wh, "SSRC2-" + N, 900_000);
        Fixtures.plastic(new ContainerService(), whMgr, wh, "SJ2-" + N, 1000, 1000, "SSRC2-" + N);
        long pending = count("SELECT count(*) FROM sync_outbox WHERE synced_at IS NULL");
        assertTrue(pending > 0);

        assertFalse(agent("http://127.0.0.1:1", "key").syncOnce()); // nothing listening
        assertEquals(pending, count("SELECT count(*) FROM sync_outbox WHERE synced_at IS NULL"));
        assertTrue(count("SELECT count(*) FROM sync_outbox WHERE synced_at IS NULL AND attempts > 0 AND last_error IS NOT NULL") > 0);

        forcedStatus.set(401);
        assertFalse(agent(baseUrl, "wrong").syncOnce());
        assertEquals(pending, count("SELECT count(*) FROM sync_outbox WHERE synced_at IS NULL"));
        forcedStatus.set(200);

        assertTrue(agent(baseUrl, "key").syncOnce()); // recovers and delivers
        assertEquals(0, count("SELECT count(*) FROM sync_outbox WHERE synced_at IS NULL"));
    }

    @Test @Order(4)
    void pulledTransferCanBeReceivedAndIsNotEchoedBack() throws Exception {
        // As the destination site: the warehouse dispatched J-IN to us; central hands us the transfer + container.
        ObjectNode container = M.createObjectNode();
        container.put("id", UUID.randomUUID().toString()).put("barcode", "SJIN-" + N).put("type", "JERRICAN_PLASTIC")
                .put("capacityMl", 1000.0).put("currentVolumeMl", 1000.0).put("topSeal", "T9").put("bottomSeal", "B9")
                .put("status", "IN_TRANSIT").put("siteId", wh.id().toString());
        ObjectNode transfer = M.createObjectNode();
        transfer.putObject("transfer").put("id", TRANSFER_ID.toString()).put("fromSiteId", wh.id().toString())
                .put("toSiteId", dest.id().toString()).put("status", "DISPATCHED").put("dispatchedAt", "2026-10-14T09:00:00");
        transfer.putArray("items").addObject().put("barcode", "SJIN-" + N).put("received", false);
        ObjectNode resp = M.createObjectNode();
        resp.putArray("transfers").add(transfer);
        resp.putArray("containers").add(container);
        resp.put("cursor", 42);
        pullBody.set(M.writeValueAsString(resp));

        assertTrue(agent(baseUrl, "key").syncOnce());
        assertEquals(1, count("SELECT count(*) FROM transfers WHERE id = ? AND status = 'DISPATCHED'", TRANSFER_ID));
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'IN_TRANSIT'", "SJIN-" + N));
        // applying pulled data must not create outbox rows (that would bounce it back to the server forever)
        assertEquals(0, count("SELECT count(*) FROM sync_outbox WHERE entity_id = ?", TRANSFER_ID.toString()));
        assertEquals(0, count("SELECT count(*) FROM sync_outbox WHERE payload ->> 'barcode' = ?", "SJIN-" + N));

        // the next pull continues from the stored cursor, and re-delivery of the same transfer is harmless
        assertTrue(agent(baseUrl, "key").syncOnce());
        assertEquals("cursor=42", pullQueries.get(pullQueries.size() - 1));
        assertEquals(1, count("SELECT count(*) FROM transfer_items WHERE transfer_id = ?", TRANSFER_ID));

        // the destination supervisor can now do the normal receive workflow
        Transfer t = new TransferService().incoming(dest.id()).stream().filter(x -> x.id().equals(TRANSFER_ID)).findFirst().orElseThrow();
        new ShiftService().start(supervisor, dest);                      // a supervisor works with containers during a shift only
        new TransferService().receive(supervisor, t, List.of("SJIN-" + N), SIG);
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'SEALED' AND site_id = ?", "SJIN-" + N, dest.id()));

        // a stale central copy must not overwrite a container that is now in our custody
        pullBody.set(M.writeValueAsString(resp));
        assertTrue(agent(baseUrl, "key").syncOnce());
        assertEquals(1, count("SELECT count(*) FROM containers WHERE barcode = ? AND status = 'SEALED' AND site_id = ?", "SJIN-" + N, dest.id()));
        assertEquals(1, count("SELECT count(*) FROM transfers WHERE id = ? AND status = 'RECEIVED'", TRANSFER_ID));
    }

    @Test @Order(5)
    void masterDataRetriesAUserWhoseSiteHasNotArrived() {
        MockConsumer<String, String> mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition("fuelmarking.master-data", 0);
        mock.assign(List.of(tp));
        mock.updateBeginningOffsets(Map.of(tp, 0L));
        UUID siteId = UUID.randomUUID(), userId = UUID.randomUUID(), omcId = UUID.randomUUID();
        String user = msg("USER", "{\"id\":\"" + userId + "\",\"username\":\"mu" + N + "\",\"fullName\":\"Mia\",\"role\":\"SUPERVISOR\",\"siteId\":\"" + siteId + "\",\"active\":true}");
        String site = msg("SITE", "{\"id\":\"" + siteId + "\",\"code\":\"MS" + N + "\",\"name\":\"Synced Site\",\"siteType\":\"MOBILE\",\"active\":true}");
        String omc = msg("OMC", "{\"id\":\"" + omcId + "\",\"code\":\"MO" + N + "\",\"name\":\"Synced Oil\",\"active\":true}");

        try (MasterDataConsumer consumer = new MasterDataConsumer(mock)) {
            // the user arrives alone, before its site: parked, and the offset is not committed past it
            mock.addRecord(new ConsumerRecord<>(tp.topic(), 0, 0L, "USER:" + userId, user));
            assertEquals(0, consumer.pollOnce(Duration.ofMillis(10)));
            assertEquals(0, count("SELECT count(*) FROM app_users WHERE id = ?", userId));
            OffsetAndMetadata c0 = mock.committed(Set.of(tp)).get(tp);
            assertTrue(c0 == null || c0.offset() == 0, "must not commit past a record that could not be applied");

            // the site arrives later (plus junk): the parked user is retried and both are applied
            mock.addRecord(new ConsumerRecord<>(tp.topic(), 0, 1L, "SITE:" + siteId, site));
            mock.addRecord(new ConsumerRecord<>(tp.topic(), 0, 2L, "JUNK", "not json"));
            assertEquals(2, consumer.pollOnce(Duration.ofMillis(10)));
            assertEquals(3L, mock.committed(Set.of(tp)).get(tp).offset());

            // out-of-order within one batch (user before site, as after topic compaction) also resolves
            UUID site2 = UUID.randomUUID(), user2 = UUID.randomUUID();
            mock.addRecord(new ConsumerRecord<>(tp.topic(), 0, 3L, "USER:" + user2, msg("USER", "{\"id\":\"" + user2 + "\",\"username\":\"mv" + N + "\",\"fullName\":\"Max\",\"role\":\"DOSING_OPERATOR\",\"siteId\":\"" + site2 + "\"}")));
            mock.addRecord(new ConsumerRecord<>(tp.topic(), 0, 4L, "SITE:" + site2, msg("SITE", "{\"id\":\"" + site2 + "\",\"code\":\"MT" + N + "\",\"name\":\"Second\",\"siteType\":\"FIXED\"}")));
            mock.addRecord(new ConsumerRecord<>(tp.topic(), 0, 5L, "OMC:" + omcId, omc));
            assertEquals(3, consumer.pollOnce(Duration.ofMillis(10)));
            assertEquals(6L, mock.committed(Set.of(tp)).get(tp).offset());
            assertEquals(1, count("SELECT count(*) FROM app_users WHERE id = ?", user2));
        }
        assertEquals(1, count("SELECT count(*) FROM app_users WHERE id = ? AND full_name = 'Mia'", userId));
        assertEquals(1, count("SELECT count(*) FROM marking_sites WHERE id = ? AND site_type = 'MOBILE'", siteId));
        assertEquals(1, count("SELECT count(*) FROM oil_marketing_companies WHERE id = ?", omcId));
        assertEquals(0, count("SELECT count(*) FROM sync_outbox WHERE entity_id = ?", siteId.toString()), "master data must not be pushed back");
    }

    static String msg(String type, String payload) { return "{\"type\":\"" + type + "\",\"op\":\"UPSERT\",\"payload\":" + payload + "}"; }
}
