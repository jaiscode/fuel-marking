package org.jai.fuelmarking.central.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jai.fuelmarking.central.sync.SyncDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class SyncService {
    static final Set<String> TYPES = Set.of("SHIFT", "TRUCK_MARKING", "SIGNATURE", "INCIDENT", "CONTAINER", "TRANSFER", "STOCK_COUNT");
    static final int MAX_BATCH = 500;
    static final int MAX_PULL = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CustodyConflicts conflicts;
    private final TransactionTemplate tx;

    public SyncService(JdbcTemplate jdbc, ObjectMapper mapper, CustodyConflicts conflicts, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.conflicts = conflicts;
        this.tx = new TransactionTemplate(txm);
    }

    /**
     * Stores a batch in one transaction, then runs the custody-conflict check in a transaction of its own. Not
     * {@code @Transactional} on purpose: the push's connection must go back to the pool BEFORE the check takes one, or
     * many concurrent pushes each hold one connection while waiting for a second and starve the whole application
     * (pulls, the web app, the sequencer). Called inside a caller's transaction (tests do this to hold a push open), both
     * steps join that transaction instead.
     */
    public PushResponse push(UUID siteId, List<Event> events) {
        if (events == null || events.isEmpty()) return new PushResponse(List.of(), List.of());
        if (events.size() > MAX_BATCH) throw new IllegalArgumentException("At most " + MAX_BATCH + " events per push");
        List<Long> acked = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        Set<String> changedTransfers = new LinkedHashSet<>();
        tx.executeWithoutResult(status -> {
            for (Event e : events) {
                String problem = validate(siteId, e);
                if (problem == null && "SIGNATURE".equals(e.entityType())) problem = signedRecordProblem(siteId, e.payload());
                if (problem == null) problem = upsert(siteId, e, changedTransfers);
                if (problem != null) rejected.add(new Rejection(e.outboxId(), problem));
                else acked.add(e.outboxId());
            }
        });
        // After the push has COMMITTED, separately and fail-open: a database error inside the push transaction would abort
        // it and the site would retry the same batch forever. Running after the whole batch also lets the site's own
        // container updates in it count. A retry that changed nothing is not re-checked.
        if (!changedTransfers.isEmpty()) conflicts.checkFailOpen(siteId, changedTransfers);
        return new PushResponse(acked, rejected);
    }

    /**
     * A site may sign only its own records (for a transfer: one it is a party to). A signature whose record is not stored
     * yet is accepted - the desktop queues it right after the record, so the record comes later in the batch or in a later
     * push. Runs inside the push transaction, so it sees the batch's earlier events.
     */
    private String signedRecordProblem(UUID siteId, JsonNode sig) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT source_site_id::text AS owner, payload -> 'transfer' ->> 'fromSiteId' AS from_site,
                       payload -> 'transfer' ->> 'toSiteId' AS to_site
                FROM synced_entities WHERE entity_type = ? AND entity_id = ?""",
                sig.path("entityType").asText(), sig.path("entityId").asText());
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        String site = siteId.toString();
        boolean allowed = "TRANSFER".equals(sig.path("entityType").asText())
                ? site.equals(row.get("from_site")) || site.equals(row.get("to_site"))
                : site.equals(row.get("owner"));
        return allowed ? null : "Signature is for another site's record";
    }

    /** A site may only write its own records; transfers may be written by either end. */
    private String validate(UUID siteId, Event e) {
        if (e.entityType() == null || !TYPES.contains(e.entityType())) return "Unknown entity type " + e.entityType();
        if (e.entityId() == null || e.entityId().isBlank() || e.entityId().length() > 64) return "Bad entity id";
        if (e.payload() == null || !e.payload().isObject()) return "Payload must be a JSON object";
        if (!"UPSERT".equals(e.operation())) return "Unsupported operation " + e.operation();
        String when = e.createdAt() == null ? "missing" : PayloadChecks.timestamp(e.createdAt());
        if (when != null) return "Bad createdAt (" + when + ")";
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

    /** Writes the event; returns a rejection reason, or null when stored or harmlessly ignored (a retry or older version). */
    private String upsert(UUID siteId, Event e, Set<String> changedTransfers) {
        // Who may change a stored row is decided here, on the locked row, so it is race-safe against a concurrent first
        // insert of the same id:
        //  - CONTAINER and TRANSFER are shared: custody changes hands, so across sites the latest arrival wins - but a
        //    transfer's two parties are fixed by its first write (no site can take one over by its id);
        //  - every other type belongs to the site that first stored it: only that site may update it;
        //  - within one site, retries and reordering must not regress state (outbox ids only grow);
        //  - a signature never changes once stored.
        // seq is left NULL (also on update, so the new version is delivered again): SyncSequencer numbers the row after this
        // transaction commits. Taking nextval here would let a slow push commit a number pullers have already passed.
        int written = jdbc.update("""
                INSERT INTO synced_entities(entity_type, entity_id, source_site_id, outbox_id, operation, payload, source_created_at, seq)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, NULL)
                ON CONFLICT (entity_type, entity_id) DO UPDATE SET
                    source_site_id = EXCLUDED.source_site_id, outbox_id = EXCLUDED.outbox_id, operation = EXCLUDED.operation,
                    payload = EXCLUDED.payload, source_created_at = EXCLUDED.source_created_at,
                    received_at = now(), seq = NULL
                WHERE ((EXCLUDED.entity_type IN ('CONTAINER', 'TRANSFER') AND synced_entities.source_site_id <> EXCLUDED.source_site_id)
                       OR (synced_entities.source_site_id = EXCLUDED.source_site_id AND synced_entities.outbox_id < EXCLUDED.outbox_id))
                  AND (EXCLUDED.entity_type <> 'TRANSFER'
                       OR (synced_entities.payload -> 'transfer' ->> 'fromSiteId' = EXCLUDED.payload -> 'transfer' ->> 'fromSiteId'
                           AND synced_entities.payload -> 'transfer' ->> 'toSiteId' = EXCLUDED.payload -> 'transfer' ->> 'toSiteId'))
                  AND (EXCLUDED.entity_type <> 'SIGNATURE' OR synced_entities.payload = EXCLUDED.payload)""",
                e.entityType(), e.entityId(), siteId, e.outboxId(), e.operation(), e.payload().toString(),
                LocalDateTime.parse(e.createdAt()));   // bound as LocalDateTime: no java.sql.Timestamp calendar shift for old dates
        if (written > 0) {
            if ("TRANSFER".equals(e.entityType())) changedTransfers.add(e.entityId());
            return null;
        }
        // Nothing written: a harmless retry or older version, or a push the rules above refuse. Ownership and transfer
        // parties never change once stored, so this read cannot race with the check above.
        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT source_site_id::text AS owner, payload = ?::jsonb AS same,
                       payload -> 'transfer' ->> 'fromSiteId' AS from_site, payload -> 'transfer' ->> 'toSiteId' AS to_site
                FROM synced_entities WHERE entity_type = ? AND entity_id = ?""", e.payload().toString(), e.entityType(), e.entityId());
        String site = siteId.toString();
        switch (e.entityType()) {
            case "CONTAINER" -> { return null; }
            case "TRANSFER" -> {
                if (!site.equals(stored.get("from_site")) && !site.equals(stored.get("to_site"))) return "Transfer belongs to other sites";
                JsonNode t = e.payload().path("transfer");
                if (!t.path("fromSiteId").asText().equals(stored.get("from_site")) || !t.path("toSiteId").asText().equals(stored.get("to_site")))
                    return "Transfer parties cannot change";
                return null;
            }
            default -> {
                if (!site.equals(stored.get("owner"))) return "Record belongs to another site";
                if ("SIGNATURE".equals(e.entityType()) && !Boolean.TRUE.equals(stored.get("same"))) return "Signatures cannot be changed";
                return null;
            }
        }
    }

    /**
     * Transfers involving the site that were last written by someone else, newer than the cursor, together with
     * the latest known state of every container they mention. Rows not yet numbered by {@link SyncSequencer} are left
     * for a later pull, so the cursor can never move past a row that commits afterwards.
     */
    @Transactional(readOnly = true)
    public PullResponse pull(UUID siteId, long cursor) {
        String site = siteId.toString();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT seq, payload::text AS payload FROM synced_entities
                WHERE entity_type = 'TRANSFER' AND seq IS NOT NULL AND seq > ? AND source_site_id <> ?::uuid
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
