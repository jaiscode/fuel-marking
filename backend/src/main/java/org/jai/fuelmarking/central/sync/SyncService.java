package org.jai.fuelmarking.central.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jai.fuelmarking.central.sync.SyncDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class SyncService {
    static final Set<String> TYPES = Set.of("SHIFT", "TRUCK_MARKING", "SIGNATURE", "INCIDENT", "CONTAINER", "TRANSFER", "STOCK_COUNT");
    static final int MAX_BATCH = 500;
    static final int MAX_PULL = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SyncService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public PushResponse push(UUID siteId, List<Event> events) {
        if (events == null || events.isEmpty()) return new PushResponse(List.of(), List.of());
        if (events.size() > MAX_BATCH) throw new IllegalArgumentException("At most " + MAX_BATCH + " events per push");
        List<Long> acked = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        for (Event e : events) {
            String problem = validate(siteId, e);
            if (problem != null) { rejected.add(new Rejection(e.outboxId(), problem)); continue; }
            upsert(siteId, e);
            acked.add(e.outboxId());
        }
        return new PushResponse(acked, rejected);
    }

    /** A site may only write its own records; transfers may be written by either end. */
    private String validate(UUID siteId, Event e) {
        if (e.entityType() == null || !TYPES.contains(e.entityType())) return "Unknown entity type " + e.entityType();
        if (e.entityId() == null || e.entityId().isBlank() || e.entityId().length() > 64) return "Bad entity id";
        if (e.payload() == null || !e.payload().isObject()) return "Payload must be a JSON object";
        if (!"UPSERT".equals(e.operation())) return "Unsupported operation " + e.operation();
        try { LocalDateTime.parse(e.createdAt()); } catch (RuntimeException ex) { return "Bad createdAt"; }
        String shape = PayloadChecks.check(e.entityType(), e.entityId(), e.payload());
        if (shape != null) return shape;
        String site = siteId.toString();
        switch (e.entityType()) {
            case "TRANSFER" -> {
                JsonNode t = e.payload().path("transfer");
                if (!site.equals(t.path("fromSiteId").asText()) && !site.equals(t.path("toSiteId").asText()))
                    return "Transfer does not involve the pushing site";
                if (!t.path("id").asText().equals(e.entityId())) return "Transfer id mismatch";
            }
            case "SHIFT", "TRUCK_MARKING", "INCIDENT", "CONTAINER", "STOCK_COUNT" -> {
                String owner = e.payload().path("siteId").asText();
                if (!site.equals(owner)) return e.entityType() + " belongs to another site";
            }
            default -> { }
        }
        return null;
    }

    private void upsert(UUID siteId, Event e) {
        // Same-site retries/reordering must not regress state; across sites the latest arrival wins (custody changes hands).
        jdbc.update("""
                INSERT INTO synced_entities(entity_type, entity_id, source_site_id, outbox_id, operation, payload, source_created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (entity_type, entity_id) DO UPDATE SET
                    source_site_id = EXCLUDED.source_site_id, outbox_id = EXCLUDED.outbox_id, operation = EXCLUDED.operation,
                    payload = EXCLUDED.payload, source_created_at = EXCLUDED.source_created_at,
                    received_at = now(), seq = nextval('synced_entities_seq')
                WHERE synced_entities.source_site_id <> EXCLUDED.source_site_id
                   OR synced_entities.outbox_id < EXCLUDED.outbox_id""",
                e.entityType(), e.entityId(), siteId, e.outboxId(), e.operation(), e.payload().toString(),
                Timestamp.valueOf(LocalDateTime.parse(e.createdAt())));
    }

    /**
     * Transfers involving the site that were last written by someone else, newer than the cursor, together with
     * the latest known state of every container they mention.
     */
    @Transactional(readOnly = true)
    public PullResponse pull(UUID siteId, long cursor) {
        String site = siteId.toString();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT seq, payload::text AS payload FROM synced_entities
                WHERE entity_type = 'TRANSFER' AND seq > ? AND source_site_id <> ?::uuid
                  AND (payload -> 'transfer' ->> 'toSiteId' = ? OR payload -> 'transfer' ->> 'fromSiteId' = ?)
                ORDER BY seq LIMIT ?""", cursor, site, site, site, MAX_PULL);
        List<JsonNode> transfers = new ArrayList<>();
        Set<String> barcodes = new LinkedHashSet<>();
        long next = cursor;
        for (Map<String, Object> r : rows) {
            JsonNode payload = read((String) r.get("payload"));
            transfers.add(payload);
            payload.path("items").forEach(i -> barcodes.add(i.path("barcode").asText()));
            next = Math.max(next, ((Number) r.get("seq")).longValue());
        }
        List<JsonNode> containers = new ArrayList<>();
        if (!barcodes.isEmpty()) {
            jdbc.query("SELECT payload::text FROM synced_entities WHERE entity_type = 'CONTAINER' AND payload ->> 'barcode' = ANY (?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", barcodes.toArray())),
                    rs -> { containers.add(read(rs.getString(1))); });
        }
        return new PullResponse(transfers, containers, next);
    }

    private JsonNode read(String json) {
        try { return mapper.readTree(json); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
