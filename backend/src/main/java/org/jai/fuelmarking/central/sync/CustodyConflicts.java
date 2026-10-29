package org.jai.fuelmarking.central.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jai.fuelmarking.central.auth.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Flags pushed transfers that contradict what central knows about a container's custody. Sites work offline, so such a
 * push is still ACCEPTED (latest arrival wins); it is only recorded in the audit log as {@value #ACTION} for head office
 * to resolve. Checked for every transfer whose stored state is DISPATCHED (open) after a push wrote it:
 * <ul>
 *   <li>(a) a container in it is also in another open transfer from a different sender;</li>
 *   <li>(b) central's latest record of the container names a site other than the sender, and the last transfer that
 *       carried it did not go to the sender either.</li>
 * </ul>
 * Runs after the push has committed (and returned its connection), in its own transaction, and never fails the push
 * (see {@link #checkFailOpen}).
 * Known blind spots, by design: the same sender dispatching one container twice; a transfer left DISPATCHED forever
 * (it keeps "holding" its containers for check (a)); two conflicting pushes committing at the same moment, before
 * either check runs (no lock on the push path); a transfer whose first arrival at central is already RECEIVED.
 */
@Component
class CustodyConflicts {
    private static final Logger log = LoggerFactory.getLogger(CustodyConflicts.class);
    static final String ACTION = "SYNC_CONFLICT";
    /** At most this many conflict rows per push, plus one "suppressed" row, so one site cannot flood the audit log. */
    static final int MAX_ROWS_PER_PUSH = 50;
    /** At most this many container checks per push (a push can carry 500 transfers x 1000 items). */
    static final int MAX_BARCODES_PER_PUSH = 5000;
    private static final int MAX_TARGET = 200, MAX_DETAIL = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final TransactionTemplate tx;

    CustodyConflicts(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    /** Fail-open: a problem here is logged and the (already committed) push stands. */
    void checkFailOpen(UUID pushingSite, Collection<String> transferIds) {
        try {
            tx.executeWithoutResult(status -> check(pushingSite, transferIds));
        } catch (RuntimeException e) {
            log.warn("Custody conflict check failed for a push by site {}; the push itself is stored", pushingSite, e);
        }
    }

    private record Conflict(String barcode, String reason) {}

    void check(UUID pushingSite, Collection<String> transferIds) {
        Map<String, JsonNode> stored = new LinkedHashMap<>();
        jdbc.query("SELECT entity_id, payload::text FROM synced_entities WHERE entity_type = 'TRANSFER' AND entity_id = ANY (?)",
                textArray(1, transferIds), rs -> { stored.put(rs.getString(1), read(rs.getString(2))); });
        List<Conflict> found = new ArrayList<>();
        int budget = MAX_BARCODES_PER_PUSH, unchecked = 0;
        for (String id : transferIds) {
            JsonNode p = stored.get(id);
            if (p == null || !"DISPATCHED".equals(p.path("transfer").path("status").asText())) continue;
            String sender = p.path("transfer").path("fromSiteId").asText();
            Set<String> unique = new LinkedHashSet<>();
            p.path("items").forEach(i -> unique.add(i.path("barcode").asText()));
            List<String> barcodes = new ArrayList<>(unique);
            if (barcodes.size() > budget) {
                unchecked++;
                barcodes = barcodes.subList(0, budget);
            }
            budget -= barcodes.size();
            if (!barcodes.isEmpty()) found.addAll(conflictsOf(id, sender, barcodes));
        }
        record(pushingSite, found, unchecked);
    }

    private List<Conflict> conflictsOf(String transferId, String sender, List<String> barcodes) {
        Map<String, String> reasons = new LinkedHashMap<>();
        // (a) one query for all of the transfer's containers (one GIN probe per barcode)
        jdbc.query("""
                SELECT DISTINCT ON (f.barcode) f.barcode, t.entity_id, t.payload -> 'transfer' ->> 'fromSiteId'
                FROM unnest(?::text[]) AS f(barcode)
                JOIN synced_entities t ON t.entity_type = 'TRANSFER'
                 AND t.payload -> 'items' @> jsonb_build_array(jsonb_build_object('barcode', f.barcode))
                WHERE t.entity_id <> ? AND t.payload -> 'transfer' ->> 'status' = 'DISPATCHED'
                  AND t.payload -> 'transfer' ->> 'fromSiteId' <> ?
                ORDER BY f.barcode, t.entity_id""",
                ps -> { ps.setArray(1, ps.getConnection().createArrayOf("text", barcodes.toArray())); ps.setString(2, transferId); ps.setString(3, sender); },
                rs -> { reasons.put(rs.getString(1), openTwice(transferId, sender, rs.getString(2), rs.getString(3))); });

        // (b) only for containers (a) did not already flag: one reason per container per transfer
        List<String> rest = barcodes.stream().filter(b -> !reasons.containsKey(b)).toList();
        Map<String, String> holders = new HashMap<>();
        if (!rest.isEmpty()) {
            jdbc.query("""
                    SELECT DISTINCT ON (payload ->> 'barcode') payload ->> 'barcode', payload ->> 'siteId' FROM synced_entities
                    WHERE entity_type = 'CONTAINER' AND payload ->> 'barcode' = ANY (?)
                    ORDER BY payload ->> 'barcode', received_at DESC""",
                    textArray(1, rest), rs -> { holders.put(rs.getString(1), rs.getString(2)); });
        }
        // a holder we know nothing about (no record, or no site on it) is not evidence of a conflict
        List<String> elsewhere = rest.stream().filter(b -> holders.get(b) != null && !sender.equals(holders.get(b))).toList();
        if (!elsewhere.isEmpty()) {
            Map<String, String> lastDestination = new HashMap<>();
            // latest by received_at (validated server time), never by casting site-supplied text
            jdbc.query("""
                    SELECT DISTINCT ON (f.barcode) f.barcode, t.payload -> 'transfer' ->> 'toSiteId'
                    FROM unnest(?::text[]) AS f(barcode)
                    JOIN synced_entities t ON t.entity_type = 'TRANSFER'
                     AND t.payload -> 'items' @> jsonb_build_array(jsonb_build_object('barcode', f.barcode))
                    WHERE t.entity_id <> ?
                    ORDER BY f.barcode, t.received_at DESC, t.entity_id""",
                    ps -> { ps.setArray(1, ps.getConnection().createArrayOf("text", elsewhere.toArray())); ps.setString(2, transferId); },
                    rs -> { lastDestination.put(rs.getString(1), rs.getString(2)); });
            for (String b : elsewhere) {
                if (sender.equals(lastDestination.get(b))) continue;   // it arrived at the sender; its own container update is just not here yet
                reasons.put(b, "Transfer " + transferId + " dispatched by site " + sender + " but central records the container at site " + holders.get(b));
            }
        }
        List<Conflict> out = new ArrayList<>();
        for (String b : barcodes) if (reasons.containsKey(b)) out.add(new Conflict(b, reasons.get(b)));
        return out;
    }

    /** The same pair always reads the same, whichever side pushed, so a re-send is recognised as already recorded. */
    private static String openTwice(String transferId, String sender, String otherId, String otherSender) {
        String[][] pair = {{transferId, sender}, {otherId, otherSender}};
        Arrays.sort(pair, Comparator.comparing(x -> x[0]));
        return "Container is in two open transfers: " + pair[0][0] + " from site " + pair[0][1] + " and " + pair[1][0] + " from site " + pair[1][1];
    }

    /**
     * Writes each new conflict once. Uniqueness is the audit row's dedupe key (target + reason, WITHOUT the reporter), so a
     * retry, a re-send, or the other side re-sending its own transfer never adds a row, even concurrently. The key therefore
     * depends on the exact reason wording above: rewording a reason makes already-recorded conflicts appear once more.
     */
    private void record(UUID pushingSite, List<Conflict> found, int uncheckedTransfers) {
        String actor = "site " + pushingSite, reporter = " (reported by site " + pushingSite + ")";
        int written = 0, suppressed = 0;
        for (Conflict c : found) {
            String target = printable(c.barcode(), MAX_TARGET), reason = cut(c.reason(), MAX_DETAIL);
            if (written == MAX_ROWS_PER_PUSH) { suppressed++; continue; }   // no lookups past the cap
            if (audit.recordOnce(actor, ACTION, target, cut(reason + reporter, MAX_DETAIL), target + "\n" + reason)) written++;
        }
        if (suppressed > 0)
            audit.recordAs(actor, ACTION, actor, suppressed + " further custody conflicts in the same push were not recorded (some may be on record already)" + reporter);
        if (uncheckedTransfers > 0)
            audit.recordAs(actor, ACTION, actor, "Custody check stopped after " + MAX_BARCODES_PER_PUSH + " containers; "
                    + uncheckedTransfers + " transfer(s) in the push were not fully checked" + reporter);
    }

    /**
     * Barcodes are stored byte-for-byte as the site sent them, but audit text must stay readable: anything that is not a
     * visible character or a plain space (a GS1 group separator, a no-break space ...) is written as a backslash, "u" and
     * four hex digits, and a backslash itself is doubled.
     */
    static String printable(String s, int max) {
        StringBuilder out = new StringBuilder(Math.min(s.length(), max));
        for (int cp : s.codePoints().toArray()) {
            String piece;
            if (cp == '\\') piece = "\\\\";
            else if (cp == ' ' || isVisible(cp)) piece = new String(Character.toChars(cp));
            else {
                StringBuilder esc = new StringBuilder();
                for (char ch : Character.toChars(cp)) esc.append(String.format("\\u%04X", (int) ch));
                piece = esc.toString();
            }
            if (out.length() + piece.length() > max) break;   // cut between characters, never inside an escape
            out.append(piece);
        }
        return out.toString();
    }

    private static boolean isVisible(int cp) {
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
                 Character.PARAGRAPH_SEPARATOR, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> false;
            default -> true;
        };
    }

    private static PreparedStatementSetter textArray(int index, Collection<String> values) {
        return ps -> ps.setArray(index, ps.getConnection().createArrayOf("text", values.toArray()));
    }

    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    private JsonNode read(String json) {
        try { return mapper.readTree(json); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
