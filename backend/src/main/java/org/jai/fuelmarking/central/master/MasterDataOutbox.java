package org.jai.fuelmarking.central.master;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Transactional outbox for master data. {@link #enqueue} runs inside the caller's transaction; {@link #drain} sends the
 * backlog to Kafka in order. If Kafka is down, nothing is lost and admin requests are never slowed down - the backlog
 * simply grows (visible in the admin API) and is delivered when the broker is back.
 */
@Component
public class MasterDataOutbox {
    private static final Logger log = LoggerFactory.getLogger(MasterDataOutbox.class);
    private static final long LOCK_ID = 727_001L;   // only one backend instance drains at a time, so order is preserved
    private static final int BATCH = 100;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MasterDataSink sink;
    private final boolean drainEnabled;

    public MasterDataOutbox(JdbcTemplate jdbc, ObjectMapper mapper, MasterDataSink sink,
                            @org.springframework.beans.factory.annotation.Value("${fuelmarking.master-data-drain-enabled:true}") boolean drainEnabled) {
        this.drainEnabled = drainEnabled;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.sink = sink;
    }

    /** Call within the transaction that changes the data. */
    public void enqueue(String type, Object id, Object payload) {
        try {
            String value = mapper.writeValueAsString(Map.of("type", type, "op", "UPSERT", "payload", payload));
            jdbc.update("INSERT INTO master_data_outbox(msg_key, payload) VALUES (?, ?)", type + ":" + id, value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + type, e);
        }
    }

    @Scheduled(fixedDelayString = "${fuelmarking.master-data-drain-ms:1000}")
    public void scheduledDrain() {
        if (!drainEnabled) return;
        try {
            drain();
        } catch (RuntimeException e) {
            log.error("Master data outbox drain failed: {}", e.getMessage());
        }
    }

    /** Sends pending messages in id order; stops at the first failure so order is kept. Returns how many were sent. */
    @Transactional
    public int drain() {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_ID);
        if (!Boolean.TRUE.equals(locked)) return 0;
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT id, msg_key, payload FROM master_data_outbox WHERE published_at IS NULL ORDER BY id LIMIT " + BATCH);
        int sent = 0;
        for (Map<String, Object> row : pending) {
            long id = ((Number) row.get("id")).longValue();
            try {
                sink.send((String) row.get("msg_key"), (String) row.get("payload"));
            } catch (Exception e) {
                String msg = describe(e);
                jdbc.update("UPDATE master_data_outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                        msg.length() > 480 ? msg.substring(0, 480) : msg, id);
                log.warn("Master data not delivered yet ({} waiting): {}", pending.size() - sent, msg);
                break;
            }
            jdbc.update("UPDATE master_data_outbox SET published_at = now(), last_error = NULL WHERE id = ?", id);
            sent++;
        }
        if (sent > 0) log.info("Sent {} master data record(s) to Kafka", sent);
        jdbc.update("DELETE FROM master_data_outbox WHERE published_at < now() - interval '7 days'");
        return sent;
    }

    /** The message plus the root cause, so an operator sees WHY (e.g. a rejected login) and not just "Send failed". */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String top = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String why = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        return root == e || top.contains(why) ? top : top + " - " + why;
    }

    public record Status(long pending, Long oldestPendingSeconds, String lastError) {}

    public Status status() {
        Map<String, Object> m = jdbc.queryForMap("""
                SELECT count(*) AS n, EXTRACT(EPOCH FROM (now() - min(created_at)))::bigint AS oldest,
                       (SELECT last_error FROM master_data_outbox WHERE published_at IS NULL AND last_error IS NOT NULL ORDER BY id LIMIT 1) AS err
                FROM master_data_outbox WHERE published_at IS NULL""");
        Number oldest = (Number) m.get("oldest");
        return new Status(((Number) m.get("n")).longValue(), oldest == null ? null : Math.max(0, oldest.longValue()), (String) m.get("err"));
    }
}
