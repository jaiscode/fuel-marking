package org.jai.fuelmarking.central.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Gives pushed TRANSFER rows their {@code synced_entities.seq} AFTER the push has committed (only transfers are pulled;
 * other entity types keep seq NULL).
 * <p>
 * Why not let the push take {@code nextval} itself (as it once did)? A sequence value is handed out when a row is written,
 * not when its transaction commits. Two sites pushing at once: push A takes 41, push B takes 42, B commits, a site pulls
 * and moves its cursor to 42, then A commits 41 - and 41 is below every cursor that already passed it, so it is never
 * delivered. Numbering rows here instead, one run at a time (advisory lock) and only rows that are already committed,
 * means every number handed out is higher than any number a puller could already have seen. Do not "simplify" this back
 * into the push, and do not lock the push path instead: that would serialise every site's pushes.
 * <p>
 * {@code synced_entities_seq} must stay {@code CACHE 1}: with a larger cache each session (and backend instance) hands out
 * numbers from its own pre-allocated range, so a later run could get a LOWER number than an earlier one.
 * <p>
 * Pushes write {@code seq = NULL}; pulls skip unnumbered rows until a run here numbers them (about a second later).
 * Scheduled tasks share a pool of 2 threads ({@code spring.task.scheduling.pool.size}), so a slow Kafka drain cannot stall this.
 */
@Component
public class SyncSequencer {
    private static final Logger log = LoggerFactory.getLogger(SyncSequencer.class);
    private static final long LOCK_ID = 727_002L;   // one numbering run at a time across backend instances (727_001 is the master-data drain)
    static final int BATCH = 1000;
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public SyncSequencer(JdbcTemplate jdbc, PlatformTransactionManager txm,
                         @Value("${fuelmarking.sync-sequencer-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${fuelmarking.sync-sequencer-ms:1000}")
    public void scheduledSequence() {
        if (!enabled) return;
        try {
            for (int i = 0; i < MAX_BATCHES_PER_RUN && sequence() == BATCH; i++) { /* keep going while there is a backlog */ }
        } catch (RuntimeException e) {
            log.error("Numbering synced transfers failed; will retry", e);
        }
    }

    /**
     * Numbers up to {@link #BATCH} committed, unnumbered transfers in one transaction, ordered by {@code received_at} (the
     * start time of the push transaction that wrote them, so only roughly arrival order - correctness does not depend on
     * it). Returns how many it numbered, or 0 if another run (on any instance) holds the lock. A programmatic transaction
     * rather than {@code @Transactional}, because the scheduled call above is a self-invocation that would bypass the
     * proxy and release the advisory lock straight away.
     */
    public int sequence() {
        Integer n = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_ID);
            if (!Boolean.TRUE.equals(locked)) return 0;
            // SKIP LOCKED: a row a push is rewriting right now is left for the next run (the push sets it back to NULL anyway)
            List<String> ids = jdbc.queryForList("""
                    SELECT entity_id FROM synced_entities WHERE entity_type = 'TRANSFER' AND seq IS NULL
                    ORDER BY received_at, entity_id LIMIT ? FOR UPDATE SKIP LOCKED""", String.class, BATCH);
            if (ids.isEmpty()) return 0;
            jdbc.batchUpdate("UPDATE synced_entities SET seq = nextval('synced_entities_seq') WHERE entity_type = 'TRANSFER' AND entity_id = ?",
                    ids, ids.size(), (ps, id) -> ps.setString(1, id));
            return ids.size();
        });
        return n == null ? 0 : n;
    }
}
