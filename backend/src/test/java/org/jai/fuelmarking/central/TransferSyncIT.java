package org.jai.fuelmarking.central;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jai.fuelmarking.central.sync.SyncDtos.Event;
import org.jai.fuelmarking.central.sync.SyncDtos.PushResponse;
import org.jai.fuelmarking.central.sync.SyncSequencer;
import org.jai.fuelmarking.central.sync.SyncService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transfer delivery between sites when pushes commit out of order, and the custody conflicts central flags (but accepts).
 * Pushes that must stay uncommitted are run in a transaction held open on another thread, so the races are deterministic.
 */
@org.springframework.test.context.TestPropertySource(locations = "classpath:it-database.properties")   // the shared test database settings
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "fuelmarking.master-data-drain-enabled=false",
        "fuelmarking.sync-sequencer-enabled=false",      // the tests number rows explicitly, at the moments they choose
        "fuelmarking.auth.audience=fuelmarking-api"})
@EmbeddedKafka(partitions = 1)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransferSyncIT {
    static final String N = UUID.randomUUID().toString().substring(0, 6);
    static UUID a, b, r, r2;
    static String aKey, bKey, rKey, r2Key;
    static long outbox = 1000;

    @org.springframework.test.context.DynamicPropertySource
    static void idp(org.springframework.test.context.DynamicPropertyRegistry reg) {
        reg.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestIdp::issuer);
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SyncService sync;
    @Autowired SyncSequencer sequencer;
    @Autowired PlatformTransactionManager txm;
    final ExecutorService pool = Executors.newCachedThreadPool();
    final List<Held> held = new ArrayList<>();

    /** Never leave a held push (and its row locks) open past a test, even a failed one. */
    @AfterEach
    void finishHeldPushes() {
        try {
            for (Held h : held) {
                h.release.countDown();
                try { h.result.get(30, TimeUnit.SECONDS); } catch (Exception ignored) { /* the test already reported it */ }
            }
        } finally {
            held.clear();
            pool.shutdownNow();
        }
    }

    @BeforeEach
    void sites() {
        if (a != null) return;
        a = newSite("TA"); aKey = apiKey(a);
        b = newSite("TB"); bKey = apiKey(b);
        r = newSite("TR"); rKey = apiKey(r);
        r2 = newSite("TS"); r2Key = apiKey(r2);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    HttpHeaders admin() { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(TestIdp.admin()); return h; }

    HttpHeaders site(UUID id, String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Site-Id", id.toString());
        h.set("X-Site-Key", key);
        return h;
    }

    JsonNode call(HttpMethod m, String url, HttpHeaders h, Object body, int status) {
        ResponseEntity<String> resp = rest.exchange(url, m, new HttpEntity<>(body, h), String.class);
        assertEquals(status, resp.getStatusCode().value(), resp.getBody());
        try { return resp.getBody() == null || resp.getBody().isBlank() ? null : mapper.readTree(resp.getBody()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    UUID newSite(String prefix) {
        return UUID.fromString(call(HttpMethod.POST, "/api/admin/sites", admin(),
                Map.of("code", prefix + N, "name", prefix + " " + N, "siteType", "FIXED"), 201).get("id").asText());
    }

    String apiKey(UUID id) { return call(HttpMethod.POST, "/api/admin/sites/" + id + "/api-key", admin(), null, 200).get("apiKey").asText(); }

    Event event(String type, String id, Map<String, Object> payload) {
        return new Event(++outbox, type, id, "UPSERT", mapper.valueToTree(payload), "2026-10-14T10:00:00");
    }

    Map<String, Object> transferOf(String kind, String dispatchedAt, String id, UUID from, UUID to, String status, String... barcodes) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String bc : barcodes) items.add(Map.of("barcode", bc, "received", "RECEIVED".equals(status)));
        return Map.of("transfer", Map.of("id", id, "fromSiteId", from.toString(), "toSiteId", to.toString(), "status", status,
                "dispatchedAt", dispatchedAt, "kind", kind), "items", items);
    }

    Map<String, Object> transfer(String id, UUID from, UUID to, String status, String... barcodes) {
        return transferOf("SUPPLY", "2026-10-14T10:00:00", id, from, to, status, barcodes);
    }

    Map<String, Object> container(String id, String barcode, UUID holder, String status) {
        return Map.of("id", id, "barcode", barcode, "siteId", holder.toString(), "status", status, "type", "JERRICAN_PLASTIC",
                "capacityMl", 1000.0, "currentVolumeMl", 1000);
    }

    /** Pushes over HTTP exactly as a desktop does; everything must be acked. */
    JsonNode push(UUID siteId, String key, Event... events) {
        JsonNode resp = call(HttpMethod.POST, "/api/sync/push", site(siteId, key), Map.of("events", List.of(events)), 200);
        assertEquals(events.length, resp.get("acked").size(), resp.toString());
        assertEquals(0, resp.get("rejected").size(), resp.toString());
        return resp;
    }

    /** A push whose transaction stays open (uncommitted) on another thread until {@link Held#commit()}. */
    final class Held {
        final CountDownLatch release = new CountDownLatch(1);
        final Future<PushResponse> result;

        Held(UUID siteId, List<Event> events) throws Exception {
            CountDownLatch pushed = new CountDownLatch(1);
            result = pool.submit(() -> new TransactionTemplate(txm).execute(s -> {
                try { return sync.push(siteId, events); }
                finally {
                    pushed.countDown();
                    try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            }));
            held.add(this);
            assertTrue(pushed.await(30, TimeUnit.SECONDS), "held push did not run");
        }

        PushResponse commit() throws Exception {
            release.countDown();
            return result.get(30, TimeUnit.SECONDS);
        }
    }

    /** A desktop's view: what it has applied, and its cursor. */
    final class Receiver {
        final UUID id; final String key;
        long cursor = 0;
        final Set<String> delivered = new HashSet<>();   // "transferId:status"

        Receiver(UUID id, String key) { this.id = id; this.key = key; }

        /** One pull, applied like the desktop does: remember the transfers, move the cursor to what the server returned. */
        List<String> pull() {
            JsonNode resp = call(HttpMethod.GET, "/api/sync/pull?cursor=" + cursor, site(id, key), null, 200);
            List<String> got = new ArrayList<>();
            for (JsonNode t : resp.get("transfers")) got.add(t.path("transfer").path("id").asText() + ":" + t.path("transfer").path("status").asText());
            long next = resp.get("cursor").asLong();
            assertTrue(next >= cursor, "the cursor must never go backwards");
            if (!got.isEmpty()) cursor = next;
            delivered.addAll(got);
            assertNothingSkipped();
            return got;
        }

        /** Every committed, numbered version at or below the cursor that is meant for this site must have been handed over. */
        void assertNothingSkipped() {
            List<String> behind = jdbc.queryForList("""
                    SELECT entity_id || ':' || (payload -> 'transfer' ->> 'status') FROM synced_entities
                    WHERE entity_type = 'TRANSFER' AND seq <= ? AND source_site_id <> ?::uuid
                      AND (payload -> 'transfer' ->> 'toSiteId' = ? OR payload -> 'transfer' ->> 'fromSiteId' = ?)""",
                    String.class, cursor, id.toString(), id.toString(), id.toString());
            Set<String> skipped = new TreeSet<>(behind);
            skipped.removeAll(delivered);
            assertTrue(skipped.isEmpty(), "cursor " + cursor + " has moved past transfer versions that were never delivered (lost for good): " + skipped);
        }
    }

    /** Numbers everything committed so far (another cached test context's scheduled run may be numbering at the same time). */
    void settle() {
        long end = System.currentTimeMillis() + 10_000;
        while (sequencer.sequence() > 0 || jdbc.queryForObject("SELECT count(*) FROM synced_entities WHERE entity_type = 'TRANSFER' AND seq IS NULL", Long.class) > 0)
            assertTrue(System.currentTimeMillis() < end, "rows left unnumbered");
    }

    int committedCount(String transferId) {
        return jdbc.queryForObject("SELECT count(*) FROM synced_entities WHERE entity_type = 'TRANSFER' AND entity_id = ?", Integer.class, transferId);
    }

    String committedStatus(String transferId) {
        return jdbc.queryForObject("SELECT payload -> 'transfer' ->> 'status' FROM synced_entities WHERE entity_type = 'TRANSFER' AND entity_id = ?",
                String.class, transferId);
    }

    List<String> conflicts(String barcode) {
        return jdbc.queryForList("SELECT detail FROM audit_log WHERE action = 'SYNC_CONFLICT' AND target = ? ORDER BY id", String.class, barcode);
    }

    // ---- the ordering bug ----------------------------------------------------------------------------------------

    @Test @Order(1)
    void aTransferCommittedAfterALaterOneIsStillDelivered() throws Exception {
        String ta = UUID.randomUUID().toString(), tb = UUID.randomUUID().toString();
        Receiver receiver = new Receiver(r, rKey);

        // site A's push starts first (and, before the fix, takes the lower seq) but is still in flight...
        Held pushA = new Held(a, List.of(event("TRANSFER", ta, transfer(ta, a, r, "DISPATCHED", "OA-" + N))));
        assertEquals(0, committedCount(ta), "push A must still be uncommitted");
        // ...while site B's push starts later and commits first
        push(b, bKey, event("TRANSFER", tb, transfer(tb, b, r, "DISPATCHED", "OB-" + N)));
        settle();

        assertEquals(List.of(tb + ":DISPATCHED"), receiver.pull(), "only B's transfer is committed so far");

        assertEquals(1, pushA.commit().acked().size());
        settle();
        receiver.pull();

        assertTrue(receiver.delivered.contains(ta + ":DISPATCHED"),
                "transfer A, committed after B, never reached the receiver: " + receiver.delivered + " cursor=" + receiver.cursor);
        assertEquals(Set.of(ta + ":DISPATCHED", tb + ":DISPATCHED"), receiver.delivered);

        // caught up: nothing new after the final cursor
        assertTrue(receiver.pull().isEmpty());
        // at-least-once: re-pulling an old range is harmless and hands back the same versions
        Receiver again = new Receiver(r, rKey);
        while (!again.pull().isEmpty()) { }
        assertEquals(receiver.delivered, again.delivered);
        assertEquals(receiver.cursor, again.cursor);
    }

    @Test @Order(2)
    void aReceiptCommittedOutOfOrderStillReachesTheSender() throws Exception {
        String t1 = UUID.randomUUID().toString(), t2 = UUID.randomUUID().toString();
        // A supplies R and R2; both transfers are delivered
        push(a, aKey, event("TRANSFER", t1, transfer(t1, a, r, "DISPATCHED", "RA-" + N)),
                event("TRANSFER", t2, transfer(t2, a, r2, "DISPATCHED", "RB-" + N)));
        settle();
        Receiver sender = new Receiver(a, aKey);
        while (!sender.pull().isEmpty()) { }   // whatever A had pending from earlier tests
        assertFalse(sender.delivered.contains(t1 + ":DISPATCHED"), "a site never gets its own writes echoed back");

        // R's RECEIVED update starts first but stays in flight; R2's starts later and commits first
        Held receiptR = new Held(r, List.of(event("TRANSFER", t1, transfer(t1, a, r, "RECEIVED", "RA-" + N))));
        assertEquals("DISPATCHED", committedStatus(t1), "R's receipt must still be uncommitted");
        push(r2, r2Key, event("TRANSFER", t2, transfer(t2, a, r2, "RECEIVED", "RB-" + N)));
        settle();
        assertEquals(List.of(t2 + ":RECEIVED"), sender.pull());

        assertEquals(1, receiptR.commit().acked().size());
        settle();
        sender.pull();
        assertTrue(sender.delivered.contains(t1 + ":RECEIVED"),
                "R's receipt, committed after R2's, never reached the sender: " + sender.delivered + " cursor=" + sender.cursor);
        assertTrue(sender.pull().isEmpty());
    }

    @Test @Order(3)
    void unnumberedRowsAreNotPulledUntilTheSequencerRuns() {
        String t = UUID.randomUUID().toString();
        Receiver receiver = new Receiver(r, rKey);
        settle();
        while (!receiver.pull().isEmpty()) { }
        push(a, aKey, event("TRANSFER", t, transfer(t, a, r, "DISPATCHED", "UN-" + N)));
        assertTrue(receiver.pull().isEmpty(), "a row is only pulled once it has been numbered");
        settle();
        assertEquals(List.of(t + ":DISPATCHED"), receiver.pull());
    }

    // ---- custody conflicts: accepted, then flagged ---------------------------------------------------------------

    @Test @Order(10)
    void twoSendersDispatchingTheSameContainerIsAcceptedAndFlaggedOnce() {
        String bc = "CA-" + N, cid = UUID.randomUUID().toString();
        String ta = UUID.randomUUID().toString(), tb = UUID.randomUUID().toString();
        push(a, aKey, event("CONTAINER", cid, container(cid, bc, a, "IN_TRANSIT")), event("TRANSFER", ta, transfer(ta, a, r, "DISPATCHED", bc)));
        assertTrue(conflicts(bc).isEmpty(), "a normal dispatch is not a conflict");

        Event fromB = event("TRANSFER", tb, transfer(tb, b, r, "DISPATCHED", bc));
        push(b, bKey, fromB);                                   // accepted: last arrival still wins
        List<String> flagged = conflicts(bc);
        assertEquals(1, flagged.size(), flagged.toString());
        String detail = flagged.get(0);
        for (String mustName : List.of(ta, tb, a.toString(), b.toString()))
            assertTrue(detail.contains(mustName), "the reason names both transfers and both senders: " + detail);
        assertTrue(detail.length() <= 500);
        assertEquals("site " + b, jdbc.queryForObject(
                "SELECT actor FROM audit_log WHERE action = 'SYNC_CONFLICT' AND target = ?", String.class, bc), "the pushing site is the actor");

        // retries of the same push, and a later re-send of the still-open transfer, do not add rows
        push(b, bKey, fromB);
        push(b, bKey, event("TRANSFER", tb, transfer(tb, b, r, "DISPATCHED", bc)));
        // nor does the other side re-sending its own (still open) transfer
        push(a, aKey, event("TRANSFER", ta, transfer(ta, a, r, "DISPATCHED", bc)));
        assertEquals(1, conflicts(bc).size(), conflicts(bc).toString());
    }

    @Test @Order(11)
    void dispatchingAContainerCentralSaysAnotherSiteHoldsIsAcceptedAndFlagged() {
        String bc = "CB-" + N, cid = UUID.randomUUID().toString(), t = UUID.randomUUID().toString();
        push(a, aKey, event("CONTAINER", cid, container(cid, bc, a, "FILLED")));
        Event dispatch = event("TRANSFER", t, transfer(t, b, r, "DISPATCHED", bc));
        push(b, bKey, dispatch);
        assertEquals(1, committedCount(t), "the push was stored");
        List<String> flagged = conflicts(bc);
        assertEquals(1, flagged.size(), flagged.toString());
        assertTrue(flagged.get(0).contains(t) && flagged.get(0).contains(a.toString()) && flagged.get(0).contains(b.toString()), flagged.get(0));

        push(b, bKey, dispatch);   // retry
        assertEquals(1, conflicts(bc).size());
    }

    @Test @Order(12)
    void normalSupplyAndReturnFlowsRaiseNoConflicts() {
        String j = "NJ-" + N, k = "NK-" + N, jid = UUID.randomUUID().toString(), kid = UUID.randomUUID().toString();
        String supply = UUID.randomUUID().toString(), back = UUID.randomUUID().toString();

        // the warehouse (A) supplies two jerricans to R: container updates before the transfer
        push(a, aKey, event("CONTAINER", jid, container(jid, j, a, "IN_TRANSIT")), event("CONTAINER", kid, container(kid, k, a, "IN_TRANSIT")),
                event("TRANSFER", supply, transferOf("SUPPLY", "2026-10-14T10:00:00", supply, a, r, "DISPATCHED", j, k)));
        // R receives; it re-registers J at once, K's container record stays as A sent it
        push(r, rKey, event("TRANSFER", supply, transferOf("SUPPLY", "2026-10-14T10:00:00", supply, a, r, "RECEIVED", j, k)),
                event("CONTAINER", jid, container(jid, j, r, "FILLED")));
        // R sends both back as empties; the container update for J comes after the transfer in the same batch
        push(r, rKey, event("TRANSFER", back, transferOf("RETURN", "2026-10-15T09:00:00", back, r, a, "DISPATCHED", j, k)),
                event("CONTAINER", jid, container(jid, j, r, "IN_TRANSIT")));
        // A receives the returns
        push(a, aKey, event("TRANSFER", back, transferOf("RETURN", "2026-10-15T09:00:00", back, r, a, "RECEIVED", j, k)),
                event("CONTAINER", jid, container(jid, j, a, "EMPTY")), event("CONTAINER", kid, container(kid, k, a, "EMPTY")));

        assertEquals(List.of(), conflicts(j));
        assertEquals(List.of(), conflicts(k));
    }

    // ---- round 2: robustness of the push path ---------------------------------------------------------------------

    JsonNode pushRaw(UUID siteId, String key, Event... events) {
        return call(HttpMethod.POST, "/api/sync/push", site(siteId, key), Map.of("events", List.of(events)), 200);
    }

    String rejection(JsonNode resp) {
        assertEquals(0, resp.get("acked").size(), resp.toString());
        assertEquals(1, resp.get("rejected").size(), resp.toString());
        return resp.get("rejected").get(0).get("reason").asText();
    }

    @Test @Order(20)
    void timesPostgresCannotStoreAreRejectedWithAReason() {
        for (String year : List.of("0000-01-01T00:00:00", "-0001-01-01T00:00:00", "+10000-01-01T00:00:00")) {
            String t = UUID.randomUUID().toString();
            String reason = rejection(pushRaw(a, aKey, event("TRANSFER", t, transferOf("SUPPLY", year, t, a, r, "DISPATCHED", "Y-" + N))));
            assertTrue(reason.contains("dispatchedAt") && reason.contains("year must be between 1 and 9999"), reason);
            assertEquals(0, committedCount(t));
        }
        String t = UUID.randomUUID().toString();
        Event badCreated = new Event(++outbox, "TRANSFER", t, "UPSERT", mapper.valueToTree(transfer(t, a, r, "DISPATCHED", "Y-" + N)), "0000-01-01T00:00:00");
        assertTrue(rejection(pushRaw(a, aKey, badCreated)).contains("createdAt"));

        // a site whose clock reset (dead RTC battery) or a typo still syncs: nothing PostgreSQL can store is refused
        String old = UUID.randomUUID().toString(), first = UUID.randomUUID().toString();
        push(a, aKey, event("TRANSFER", old, transferOf("SUPPLY", "1999-12-31T23:59:59", old, a, r, "DISPATCHED", "Y1999-" + N)));
        push(a, aKey, new Event(++outbox, "TRANSFER", first, "UPSERT",
                mapper.valueToTree(transferOf("SUPPLY", "0001-01-01T00:00:00", first, a, r, "DISPATCHED", "Y1-" + N)), "1970-01-01T00:00:00"));
        assertEquals("1970-01-01 00:00:00", jdbc.queryForObject(
                "SELECT source_created_at::text FROM synced_entities WHERE entity_type = 'TRANSFER' AND entity_id = ?", String.class, first),
                "stored exactly, no calendar shift");
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM v_transfers WHERE id = ?::uuid AND dispatched_at = '0001-01-01'", Long.class, first),
                "the typed view reads it");
    }

    @Test @Order(21)
    void transferItemsBarcodesAndIdsAreBounded() {
        String t = UUID.randomUUID().toString();
        String[] many = new String[1001];
        for (int i = 0; i < many.length; i++) many[i] = "M" + i + "-" + N;
        assertTrue(rejection(pushRaw(a, aKey, event("TRANSFER", t, transfer(t, a, r, "DISPATCHED", many)))).contains("At most 1000 items"));
        assertTrue(rejection(pushRaw(a, aKey, event("TRANSFER", t, transfer(t, a, r, "DISPATCHED", "X".repeat(101))))).contains("longer than 100"));
        for (String sneaky : List.of("AB\u202EC", "AB\u200BC", "AB\nC", "AB\u0000C", "AB\u2028C", "AB\u001BC"))
            assertTrue(rejection(pushRaw(a, aKey, event("TRANSFER", t, transfer(t, a, r, "DISPATCHED", sneaky)))).contains("control or invisible"), sneaky);
        String cid = UUID.randomUUID().toString();
        assertTrue(rejection(pushRaw(a, aKey, event("CONTAINER", cid, container(cid, "BAD\u202E" + N, a, "FILLED")))).contains("control or invisible"));
        // ids are compared as text, so a non-canonical (upper-case) UUID is refused rather than silently mismatching
        Map<String, Object> upper = Map.of("transfer", Map.of("id", t, "fromSiteId", a.toString().toUpperCase(), "toSiteId", r.toString(),
                "status", "DISPATCHED", "dispatchedAt", "2026-10-14T10:00:00"), "items", List.of());
        assertTrue(rejection(pushRaw(a, aKey, event("TRANSFER", t, upper))).contains("canonical"));
        assertEquals(0, committedCount(t));
        // what the desktop really sends still passes: letters, digits, dashes, slashes, spaces, non-Latin scripts
        String ok = UUID.randomUUID().toString();
        push(a, aKey, event("TRANSFER", ok, transfer(ok, a, r, "DISPATCHED", "JC-1", "IBC/7 A-" + N, "جريكان-" + N, "Ü-" + N, "X".repeat(100))));
    }

    @Test @Order(22)
    void aFailingConflictCheckNeverFailsOrRollsBackThePush() {
        String bc = "FAIL-" + N, cid = UUID.randomUUID().toString(), t = UUID.randomUUID().toString();
        push(a, aKey, event("CONTAINER", cid, container(cid, bc, a, "FILLED")));
        // a database error while recording the conflict (in the push transaction it would have aborted the push)
        jdbc.execute("CREATE OR REPLACE FUNCTION transfer_sync_it_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'simulated audit failure'; END $$");
        jdbc.execute("CREATE TRIGGER transfer_sync_it_fail BEFORE INSERT ON audit_log FOR EACH ROW WHEN (NEW.target = '" + bc + "') EXECUTE FUNCTION transfer_sync_it_fail()");
        try {
            push(b, bKey, event("TRANSFER", t, transfer(t, b, r, "DISPATCHED", bc)));   // all acked, HTTP 200
        } finally {
            jdbc.execute("DROP TRIGGER transfer_sync_it_fail ON audit_log");
            jdbc.execute("DROP FUNCTION transfer_sync_it_fail()");
        }
        assertEquals(1, committedCount(t), "the push is stored although its conflict check failed");
        assertEquals(List.of(), conflicts(bc));
    }

    @Test @Order(23)
    void aStoredTransferWithAnUnstorableDateDoesNotBreakOtherSitesPushes() {
        // written before validation was tightened (or by hand): its dispatchedAt cannot be cast to a PostgreSQL timestamp
        String bc = "POISON-" + N, cid = UUID.randomUUID().toString(), old = UUID.randomUUID().toString(), t = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO synced_entities(entity_type, entity_id, source_site_id, outbox_id, operation, payload, source_created_at)
                VALUES ('TRANSFER', ?, ?, 1, 'UPSERT', ?::jsonb, now())""", old, a,
                mapper.valueToTree(transferOf("SUPPLY", "0000-01-01T00:00:00", old, a, r, "RECEIVED", bc)).toString());
        try {
            push(a, aKey, event("CONTAINER", cid, container(cid, bc, a, "FILLED")));
            push(b, bKey, event("TRANSFER", t, transfer(t, b, r2, "DISPATCHED", bc)));   // runs the "last destination" lookup over the bad row
            assertEquals(1, committedCount(t));
            assertEquals(1, conflicts(bc).size(), "and the check still works: " + conflicts(bc));
        } finally {
            jdbc.update("DELETE FROM synced_entities WHERE entity_type = 'TRANSFER' AND entity_id = ?", old);   // leave no poison behind
        }
    }

    @Test @Order(24)
    void theSequencerSkipsARowAPushIsRewritingAndNumbersItAfterwards() throws Exception {
        String t = UUID.randomUUID().toString();
        settle();
        Receiver sender = new Receiver(a, aKey);
        while (!sender.pull().isEmpty()) { }
        push(a, aKey, event("TRANSFER", t, transfer(t, a, r, "DISPATCHED", "SK-" + N)));   // committed, not yet numbered
        Held receipt = new Held(r, List.of(event("TRANSFER", t, transfer(t, a, r, "RECEIVED", "SK-" + N))));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> sequencer.sequence(), "the sequencer must not wait on a push");
        assertNull(jdbc.queryForObject("SELECT seq FROM synced_entities WHERE entity_type = 'TRANSFER' AND entity_id = ?", Long.class, t),
                "a row locked by a push is left for the next run");
        assertEquals(1, receipt.commit().acked().size());
        settle();
        assertEquals("RECEIVED", committedStatus(t));
        assertNotNull(jdbc.queryForObject("SELECT seq FROM synced_entities WHERE entity_type = 'TRANSFER' AND entity_id = ?", Long.class, t));
        assertEquals(List.of(t + ":RECEIVED"), sender.pull(), "numbered with the new version");
    }

    @Test @Order(25)
    void aSiteWithACursorFromBeforeTheUpgradeStillGetsNewTransfers() {
        UUID fresh = newSite("TU"); String freshKey = apiKey(fresh);
        String old = UUID.randomUUID().toString(), t = UUID.randomUUID().toString();
        // a long history numbered the old way (at write time), ending in a transfer to this site
        jdbc.queryForObject("SELECT setval('synced_entities_seq', (SELECT last_value FROM synced_entities_seq) + 100000)", Long.class);
        jdbc.update("""
                INSERT INTO synced_entities(entity_type, entity_id, source_site_id, outbox_id, operation, payload, source_created_at, seq)
                VALUES ('TRANSFER', ?, ?, 1, 'UPSERT', ?::jsonb, now(), nextval('synced_entities_seq'))""", old, a,
                mapper.valueToTree(transfer(old, a, fresh, "DISPATCHED", "UP-" + N)).toString());
        Receiver receiver = new Receiver(fresh, freshKey);
        assertEquals(List.of(old + ":DISPATCHED"), receiver.pull());
        long before = receiver.cursor;

        push(a, aKey, event("TRANSFER", t, transfer(t, a, fresh, "DISPATCHED", "UP2-" + N)));
        settle();
        assertEquals(List.of(t + ":DISPATCHED"), receiver.pull());
        assertTrue(receiver.cursor > before);
    }

    @Test @Order(26)
    void oneFloodingPushRecordsAtMost50ConflictsPlusASummary() {
        List<Event> containers = new ArrayList<>();
        String[] bcs = new String[60];
        for (int i = 0; i < bcs.length; i++) {
            bcs[i] = "FL" + i + "-" + N;
            String cid = UUID.randomUUID().toString();
            containers.add(event("CONTAINER", cid, container(cid, bcs[i], a, "FILLED")));
        }
        push(a, aKey, containers.toArray(Event[]::new));
        String t = UUID.randomUUID().toString();
        push(b, bKey, event("TRANSFER", t, transfer(t, b, r, "DISPATCHED", bcs)));
        int rows = 0;
        for (String bc : bcs) rows += conflicts(bc).size();
        assertEquals(50, rows);
        List<String> summary = jdbc.queryForList("SELECT detail FROM audit_log WHERE action = 'SYNC_CONFLICT' AND target = ? AND detail LIKE '10 further%'",
                String.class, "site " + b);
        assertEquals(1, summary.size(), summary.toString());
        assertTrue(summary.get(0).contains("reported by site " + b));
    }

    @Test @Order(27)
    void conflictChecksPerPushAreCappedAndTheCapIsRecorded() {
        List<Event> transfers = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            String t = UUID.randomUUID().toString();
            String[] bcs = new String[1000];
            for (int i = 0; i < bcs.length; i++) bcs[i] = "CAP" + k + "x" + i + "-" + N;
            transfers.add(event("TRANSFER", t, transfer(t, r2, r, "DISPATCHED", bcs)));
        }
        push(r2, r2Key, transfers.toArray(Event[]::new));
        List<String> summary = jdbc.queryForList("SELECT detail FROM audit_log WHERE action = 'SYNC_CONFLICT' AND target = ? AND detail LIKE 'Custody check stopped%'",
                String.class, "site " + r2);
        assertEquals(1, summary.size(), summary.toString());
        assertTrue(summary.get(0).contains("1 transfer(s)"), summary.get(0));
    }

    @Test @Order(28)
    void theAdminAuditViewCanBeFilteredByAction() {
        JsonNode all = call(HttpMethod.GET, "/api/admin/audit?limit=500&action=SYNC_CONFLICT", admin(), null, 200);
        assertTrue(all.size() > 0);
        all.forEach(row -> assertEquals("SYNC_CONFLICT", row.get("action").asText()));
        JsonNode keys = call(HttpMethod.GET, "/api/admin/audit?action=API_KEY_ISSUED", admin(), null, 200);
        assertTrue(keys.size() > 0, "master-data entries stay reachable however many conflicts there are");
        keys.forEach(row -> assertEquals("API_KEY_ISSUED", row.get("action").asText()));
        assertEquals(0, call(HttpMethod.GET, "/api/admin/audit?action=x'%20OR%20'1'='1", admin(), null, 200).size(), "a parameter, not SQL");
        call(HttpMethod.GET, "/api/admin/audit?action=" + "A".repeat(51), admin(), null, 400);
        call(HttpMethod.GET, "/api/admin/audit?action=SYNC_CONFLICT", site(a, aKey), null, 401);
    }

    // ---- round 3: transfer ownership (R4) ------------------------------------------------------------------------

    @Test @Order(30)
    void aSiteCannotTakeOverAnotherPairsTransferById() {
        String t = UUID.randomUUID().toString(), own = "OWN-" + N;
        push(b, bKey, event("TRANSFER", t, transfer(t, b, r, "DISPATCHED", own)));
        settle();

        // A learns the id and pushes it as receiver, as sender, and with the real parties left alone
        Map<String, Map<String, Object>> forgeries = new LinkedHashMap<>();
        forgeries.put("A as receiver", transfer(t, b, a, "DISPATCHED", own));
        forgeries.put("A as sender", transfer(t, a, r, "DISPATCHED", "FORGED-" + N));
        forgeries.put("A not a party", transfer(t, b, r, "RECEIVED", own));
        forgeries.forEach((what, forged) -> {
            JsonNode resp = pushRaw(a, aKey, event("TRANSFER", t, forged));
            assertEquals(0, resp.get("acked").size(), what + ": a forged push must not be acked: " + resp);
            assertEquals(1, resp.get("rejected").size(), what + ": " + resp);
        });
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT source_site_id::text AS src, payload -> 'transfer' ->> 'fromSiteId' AS f, payload -> 'transfer' ->> 'toSiteId' AS t,
                       payload -> 'transfer' ->> 'status' AS st FROM synced_entities WHERE entity_type = 'TRANSFER' AND entity_id = ?""", t);
        assertEquals(Map.of("src", b.toString(), "f", b.toString(), "t", r.toString(), "st", "DISPATCHED"), row, "the stored transfer is untouched");

        settle();
        Receiver c = new Receiver(r, rKey);
        while (!c.pull().isEmpty()) { }
        assertTrue(c.delivered.contains(t + ":DISPATCHED"), "the real receiver still gets it");
        Receiver sender = new Receiver(b, bKey);
        while (!sender.pull().isEmpty()) { }
        assertTrue(sender.delivered.stream().noneMatch(v -> v.startsWith(t + ":")), "the sender never pulls a forged version: " + sender.delivered);

        // the real receiver may change the status but not the parties; its receipt (and a retry of it) still flow
        assertTrue(rejection(pushRaw(r, rKey, event("TRANSFER", t, transfer(t, a, r, "RECEIVED", own)))).contains("parties"));
        Event receipt = event("TRANSFER", t, transfer(t, b, r, "RECEIVED", own));
        push(r, rKey, receipt);
        push(r, rKey, receipt);
        settle();
        sender.pull();
        assertTrue(sender.delivered.contains(t + ":RECEIVED"), sender.delivered.toString());
        // and the sender may re-send it (e.g. after a restore) without changing who it is between
        push(b, bKey, event("TRANSFER", t, transfer(t, b, r, "RECEIVED", own)));
    }
    // ---- round 3: real-world barcodes (R2) -----------------------------------------------------------------------

    @Test @Order(31)
    void barcodesWithNoBreakSpacesAndGroupSeparatorsSyncEndToEnd() {
        Map<String, String> auditTarget = new LinkedHashMap<>();
        auditTarget.put("NB" + (char) 0xA0 + N, "NB\\u00A0" + N);              // pasted no-break space
        auditTarget.put("01" + (char) 0x1D + "21GS" + N, "01\\u001D21GS" + N);   // GS1 group separator from a scanner
        auditTarget.forEach((bc, target) -> {
            String cid = UUID.randomUUID().toString(), t = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
            push(a, aKey, event("CONTAINER", cid, container(cid, bc, a, "IN_TRANSIT")), event("TRANSFER", t, transfer(t, a, r2, "DISPATCHED", bc)));
            settle();
            // pulled back exactly as sent, container included
            JsonNode found = null, foundContainer = null;
            long cursor = 0;
            while (true) {
                JsonNode resp = call(HttpMethod.GET, "/api/sync/pull?cursor=" + cursor, site(r2, r2Key), null, 200);
                if (resp.get("transfers").isEmpty()) break;
                for (JsonNode tr : resp.get("transfers")) if (tr.path("transfer").path("id").asText().equals(t)) found = tr;
                for (JsonNode k : resp.get("containers")) if (k.path("barcode").asText().equals(bc)) foundContainer = k;
                cursor = resp.get("cursor").asLong();
            }
            assertNotNull(found, "transfer with barcode " + target + " was not pulled");
            assertEquals(bc, found.path("items").get(0).path("barcode").asText());
            assertNotNull(foundContainer, "container " + target + " came with it");
            // and a conflict on it is flagged under a readable, escaped target
            push(b, bKey, event("TRANSFER", other, transfer(other, b, r, "DISPATCHED", bc)));
            assertEquals(1, conflicts(target).size(), target);
            assertTrue(conflicts(bc).isEmpty(), "no raw control/space characters in audit text");
        });
    }
    // ---- round 4: records belong to the site that stored them (R5) ---------------------------------------------

    com.fasterxml.jackson.databind.node.ObjectNode fixture(String name) {
        try (var in = getClass().getResourceAsStream("/fixtures/" + name + ".json")) {
            return (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(in);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    /** A valid document of the given type, owned by {@code owner}. */
    com.fasterxml.jackson.databind.node.ObjectNode record(String type, String id, UUID owner) {
        com.fasterxml.jackson.databind.node.ObjectNode p = switch (type) {
            case "TRUCK_MARKING" -> fixture(type).put("uuid", id);
            case "SHIFT", "INCIDENT" -> fixture(type).put("id", id);
            case "STOCK_COUNT" -> {
                com.fasterxml.jackson.databind.node.ObjectNode sc = mapper.createObjectNode().put("id", id)
                        .put("countedBy", UUID.randomUUID().toString()).put("countedAt", "2026-10-14T10:00:00")
                        .put("totalSystemG", 1000.0).put("totalCountedG", 990.0).put("totalVarianceG", -10.0);
                sc.putArray("lines").addObject().put("barcode", "SC-" + N).put("systemG", 1000.0).put("countedG", 990.0).put("varianceG", -10.0);
                yield sc;
            }
            default -> throw new IllegalArgumentException(type);
        };
        return p.put("siteId", owner.toString());
    }

    Event event(String type, String id, JsonNode payload) {
        return new Event(++outbox, type, id, "UPSERT", payload, "2026-10-14T10:00:00");
    }

    Map<String, Object> stored(String type, String id) {
        return jdbc.queryForMap("SELECT source_site_id::text AS src, payload::text AS payload FROM synced_entities WHERE entity_type = ? AND entity_id = ?", type, id);
    }

    @Test @Order(40)
    void aSiteCannotOverwriteAnotherSitesRecordsById() {
        for (String type : List.of("SHIFT", "TRUCK_MARKING", "INCIDENT", "STOCK_COUNT")) {
            String id = UUID.randomUUID().toString();
            Event mine = event(type, id, record(type, id, a));
            push(a, aKey, mine);
            Map<String, Object> before = stored(type, id);

            // B pushes the same id as its own record
            JsonNode resp = pushRaw(b, bKey, event(type, id, record(type, id, b)));
            assertEquals(0, resp.get("acked").size(), type + ": B's takeover must not be acked: " + resp);
            assertEquals("Record belongs to another site", rejection(resp), type);
            assertEquals(before, stored(type, id), type + ": A's record is untouched");

            // A's own later version and its retries still go through
            Event update = event(type, id, record(type, id, a).put("rev", 2));
            push(a, aKey, update);
            push(a, aKey, update);
            push(a, aKey, mine);   // an older version arriving late is ignored, not refused
            assertEquals("2", jdbc.queryForObject("SELECT payload ->> 'rev' FROM synced_entities WHERE entity_type = ? AND entity_id = ?", String.class, type, id), type);
            assertEquals(a.toString(), stored(type, id).get("src"));
        }
    }

    com.fasterxml.jackson.databind.node.ObjectNode signature(String id, String entityType, String entityId, String png) {
        return mapper.createObjectNode().put("id", id).put("entityType", entityType).put("entityId", entityId).put("step", "REGISTRATION")
                .put("signedBy", UUID.randomUUID().toString()).put("signaturePng", png);
    }

    @Test @Order(41)
    void signaturesBelongToTheSignedRecordsSiteAndCannotChange() {
        String marking = UUID.randomUUID().toString(), sig = UUID.randomUUID().toString();
        com.fasterxml.jackson.databind.node.ObjectNode sigPayload = signature(sig, "TRUCK_MARKING", marking, "AQID");
        Event signed = event("SIGNATURE", sig, sigPayload.deepCopy());
        push(a, aKey, event("TRUCK_MARKING", marking, record("TRUCK_MARKING", marking, a)), signed);
        Map<String, Object> before = stored("SIGNATURE", sig);

        // B cannot overwrite A's signature, even by pointing it at a record B owns
        String bShift = UUID.randomUUID().toString();
        push(b, bKey, event("SHIFT", bShift, record("SHIFT", bShift, b)));
        JsonNode resp = pushRaw(b, bKey, event("SIGNATURE", sig, signature(sig, "SHIFT", bShift, "BAUG")));
        assertEquals(0, resp.get("acked").size(), "B's overwrite of A's signature must not be acked: " + resp);
        assertEquals("Record belongs to another site", rejection(resp));
        assertEquals(before, stored("SIGNATURE", sig));

        // B cannot sign A's truck marking
        String forged = UUID.randomUUID().toString();
        assertEquals("Signature is for another site's record", rejection(pushRaw(b, bKey, event("SIGNATURE", forged, signature(forged, "TRUCK_MARKING", marking, "BAUG")))));

        // A's identical retry (and an identical re-send) is fine; A changing the image is not
        push(a, aKey, signed);
        push(a, aKey, event("SIGNATURE", sig, sigPayload.deepCopy()));
        assertEquals("Signatures cannot be changed", rejection(pushRaw(a, aKey, event("SIGNATURE", sig, sigPayload.deepCopy().put("signaturePng", "BAUG")))));
        assertEquals(before, stored("SIGNATURE", sig));

        // a transfer is signed by either of its two sites, and by nobody else
        String t = UUID.randomUUID().toString(), dispatch = UUID.randomUUID().toString(), receipt = UUID.randomUUID().toString(), stranger = UUID.randomUUID().toString();
        push(a, aKey, event("TRANSFER", t, transfer(t, a, r, "DISPATCHED", "SG-" + N)));
        push(a, aKey, event("SIGNATURE", dispatch, signature(dispatch, "TRANSFER", t, "AQID")));
        push(r, rKey, event("SIGNATURE", receipt, signature(receipt, "TRANSFER", t, "AQID")));
        assertEquals("Signature is for another site's record", rejection(pushRaw(b, bKey, event("SIGNATURE", stranger, signature(stranger, "TRANSFER", t, "AQID")))));

        // a signature may arrive before the record it signs (later in the batch, or in a later push)
        String early = UUID.randomUUID().toString(), later = UUID.randomUUID().toString();
        push(a, aKey, event("SIGNATURE", early, signature(early, "SHIFT", later, "AQID")), event("SHIFT", later, record("SHIFT", later, a)));
        // only what the desktop signs
        String odd = UUID.randomUUID().toString();
        assertTrue(rejection(pushRaw(a, aKey, event("SIGNATURE", odd, signature(odd, "INCIDENT", UUID.randomUUID().toString(), "AQID")))).contains("entityType"));
    }

    @Test @Order(42)
    void containersStillChangeHandsBetweenSites() {
        String cid = UUID.randomUUID().toString();
        push(a, aKey, event("CONTAINER", cid, container(cid, "HO-" + N, a, "IN_TRANSIT")));
        push(b, bKey, event("CONTAINER", cid, container(cid, "HO-" + N, b, "FILLED")));
        assertEquals(b.toString(), stored("CONTAINER", cid).get("src"));
    }
}
