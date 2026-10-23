package org.jai.fuelmarking.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jai.fuelmarking.config.Tx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Store-and-forward sync with the central backend. Pushes the local outbox in order (at-least-once; the server is
 * idempotent) and pulls transfers addressed to this site. Safe to run offline: it simply retries later.
 */
public class SyncAgent implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SyncAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int BATCH = 100;
    private static final String CURSOR_KEY = "pull_cursor";

    private final URI baseUri;
    private final UUID siteId;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final InboundApplier inbound = new InboundApplier();
    private ScheduledExecutorService scheduler;
    private boolean offlineLogged;

    public SyncAgent(String baseUrl, UUID siteId, String apiKey) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.siteId = siteId;
        this.apiKey = apiKey;
    }

    public void start(int intervalSeconds) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-agent");
            t.setDaemon(true);
            return t;
        });
        schedule(intervalSeconds, 2 + java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1, intervalSeconds)));
    }

    /**
     * Runs a cycle after delaySeconds, then schedules the next one at the interval give or take 20%. Sites that all start together (after a
     * central restart, say) drift apart instead of hitting the central system in the same second every time.
     */
    private void schedule(int intervalSeconds, long delaySeconds) {
        scheduler.schedule(() -> {
            try { syncOnce(); }
            finally {
                if (!scheduler.isShutdown()) schedule(intervalSeconds, jittered(intervalSeconds));
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /** The interval varied by up to 20% either way (at least 1 second). */
    static long jittered(int intervalSeconds) {
        double factor = 0.8 + 0.4 * java.util.concurrent.ThreadLocalRandom.current().nextDouble();
        return Math.max(1, Math.round(intervalSeconds * factor));
    }

    /** One push + pull cycle. Never throws. Returns false if the central system could not be reached. */
    public boolean syncOnce() {
        try {
            int pushed;
            do { pushed = pushOnce(); } while (pushed == BATCH); // drain a backlog
            pullOnce();
            if (offlineLogged) log.info("Sync connection restored");
            offlineLogged = false;
            return true;
        } catch (IOException e) {
            if (!offlineLogged) log.warn("Central system unreachable, will retry: {}", e.getMessage() != null ? e.getMessage() : e.toString());
            offlineLogged = true;
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            log.error("Sync cycle failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /** Pushes up to one batch of pending outbox rows; returns how many were handled. */
    public int pushOnce() throws IOException, InterruptedException {
        List<Long> ids = new ArrayList<>();
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode events = body.putArray("events");
        Tx.run(c -> {
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, entity_type, entity_id, operation, payload::text, created_at " +
                         "FROM sync_outbox WHERE synced_at IS NULL ORDER BY id LIMIT " + BATCH)) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                    ObjectNode e = events.addObject();
                    e.put("outboxId", rs.getLong(1)).put("entityType", rs.getString(2)).put("entityId", rs.getString(3))
                            .put("operation", rs.getString(4));
                    try { e.set("payload", MAPPER.readTree(rs.getString(5))); }
                    catch (IOException ex) { throw new SQLException(ex); }
                    e.put("createdAt", rs.getTimestamp(6).toLocalDateTime().withNano(0).toString());
                }
            }
            return null;
        });
        if (ids.isEmpty()) return 0;

        JsonNode resp;
        try {
            resp = send(HttpRequest.newBuilder(baseUri.resolve("api/sync/push"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))));
        } catch (IOException | RuntimeException e) {
            recordFailure(ids, e.getMessage() != null ? e.getMessage() : e.toString());
            throw e;
        }
        List<Long> acked = new ArrayList<>();
        resp.path("acked").forEach(n -> acked.add(n.asLong()));
        Tx.run(c -> {
            markSynced(c, acked, null);
            for (JsonNode r : resp.path("rejected")) {
                // refused for good: stop retrying but keep the reason for investigation
                markSynced(c, List.of(r.path("outboxId").asLong()), r.path("reason").asText("rejected"));
                log.error("Central system rejected outbox #{}: {}", r.path("outboxId").asLong(), r.path("reason").asText());
            }
            return null;
        });
        return ids.size();
    }

    /** Pulls transfers addressed to this site and applies them atomically together with the new cursor. */
    public int pullOnce() throws IOException, InterruptedException {
        long cursor = Tx.call(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM sync_state WHERE key = ?")) {
                ps.setString(1, CURSOR_KEY);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Long.parseLong(rs.getString(1)) : 0L; }
            }
        });
        JsonNode resp = send(HttpRequest.newBuilder(baseUri.resolve("api/sync/pull?cursor=" + cursor)).GET());
        JsonNode transfers = resp.path("transfers");
        if (transfers.isEmpty()) return 0;
        long next = resp.path("cursor").asLong(cursor);
        try {
            Tx.run(c -> {
                for (JsonNode container : resp.path("containers")) inbound.container(c, container);
                for (JsonNode t : transfers) inbound.transfer(c, t);
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO sync_state(key, value) VALUES (?, ?)
                        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value""")) {
                    ps.setString(1, CURSOR_KEY); ps.setString(2, Long.toString(next));
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (MissingDependencyException e) {
            log.warn("Pulled transfers cannot be applied yet ({}); will retry", e.getMessage());
            return 0; // nothing committed, cursor unchanged
        }
        return transfers.size();
    }

    private JsonNode send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(request.timeout(Duration.ofSeconds(30))
                .header("X-Site-Id", siteId.toString()).header("X-Site-Key", apiKey).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 401) throw new IllegalStateException("Central system rejected this site's credentials (HTTP 401)");
        if (r.statusCode() / 100 != 2) throw new IOException("Central system answered HTTP " + r.statusCode());
        return MAPPER.readTree(r.body());
    }

    private static void markSynced(Connection c, List<Long> ids, String error) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE sync_outbox SET synced_at = now(), last_error = ? WHERE id = ?")) {
            for (long id : ids) { ps.setString(1, error); ps.setLong(2, id); ps.addBatch(); }
            ps.executeBatch();
        }
    }

    private static void recordFailure(List<Long> ids, String error) {
        try {
            Tx.run(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE sync_outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?")) {
                    for (long id : ids) { ps.setString(1, error); ps.setLong(2, id); ps.addBatch(); }
                    ps.executeBatch();
                }
                return null;
            });
        } catch (RuntimeException ignored) {
            // bookkeeping only
        }
    }

    @Override
    public void close() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
