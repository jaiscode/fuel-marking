package org.jai.fuelmarking.central.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jai.fuelmarking.central.web.ReadModels.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Read-only queries over the typed views. All user input is bound as parameters; sort/group columns are whitelisted. */
@Service
public class ReadService {
    static final int MAX_PAGE_SIZE = 200;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ReadService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Builds a WHERE clause from optional filters. */
    private static final class Filter {
        final List<String> clauses = new ArrayList<>();
        final MapSqlParameterSource params = new MapSqlParameterSource();

        Filter eq(String column, String name, Object value) {
            if (value != null && !(value instanceof String s && s.isBlank())) { clauses.add(column + " = :" + name); params.addValue(name, value); }
            return this;
        }

        Filter range(String column, LocalDate from, LocalDate to) {
            if (from != null) { clauses.add(column + " >= :from"); params.addValue("from", Timestamp.valueOf(from.atStartOfDay())); }
            if (to != null) { clauses.add(column + " < :to"); params.addValue("to", Timestamp.valueOf(to.plusDays(1).atStartOfDay())); }
            return this;
        }

        Filter search(String name, String text, String... columns) {
            if (text != null && !text.isBlank()) {
                clauses.add("(" + String.join(" OR ", Arrays.stream(columns).map(c -> c + " ILIKE :" + name).toList()) + ")");
                params.addValue(name, "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            }
            return this;
        }

        String where() { return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses); }
    }

    private static LocalDateTime ts(ResultSet rs, String c) throws SQLException {
        Timestamp t = rs.getTimestamp(c);
        return t == null ? null : t.toLocalDateTime();
    }

    private static Double nullableDouble(ResultSet rs, String c) throws SQLException {
        double v = rs.getDouble(c);
        return rs.wasNull() ? null : v;
    }

    private static Long age(ResultSet rs) throws SQLException {
        long v = rs.getLong("last_sync_age");
        return rs.wasNull() ? null : Math.max(0, v);
    }

    private static int size(int size) { return Math.max(1, Math.min(size, MAX_PAGE_SIZE)); }

    // ---------------------------------------------------------------- lookups

    public Lookups lookups() {
        RowMapper<Lookup> m = (rs, n) -> new Lookup(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getBoolean("active"), null);
        return new Lookups(jdbc.getJdbcTemplate().query("SELECT id, code, name, active FROM marking_sites ORDER BY name", m),
                jdbc.getJdbcTemplate().query("SELECT id, code, name, active FROM oil_marketing_companies ORDER BY name", m),
                jdbc.getJdbcTemplate().query("SELECT id, code, name, active FROM fuel_types ORDER BY name", m),
                jdbc.getJdbcTemplate().query("SELECT id, code, name, active, color FROM rum_types ORDER BY name",
                        (rs, n) -> new Lookup(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getBoolean("active"), rs.getString("color"))),
                jdbc.getJdbcTemplate().query("SELECT id, code, name, active FROM container_types ORDER BY category, name", m),
                jdbc.getJdbcTemplate().query("SELECT id, code, name, active FROM locations ORDER BY name", m));
    }

    // ---------------------------------------------------------------- overview

    public Overview overview(UUID siteId, LocalDate from, LocalDate to) {
        Filter f = new Filter().eq("m.site_id", "site", siteId).range("m.registered_at", from, to);
        f.clauses.add("m.status <> 'DELETED'");                 // a deleted marking counts nowhere
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT count(*) AS trucks, count(*) FILTER (WHERE m.status = 'COMPLETED') AS completed,
                       COALESCE(sum(m.declared_volume_l), 0) AS fuel, COALESCE(sum(m.rum_ml), 0) AS rum
                FROM v_truck_markings m""" + f.where(), f.params);

        Filter fi = new Filter().eq("i.site_id", "site", siteId).range("i.occurred_at", from, to);
        Map<String, Object> inc = jdbc.queryForMap("SELECT count(*) AS n, COALESCE(sum(i.quantity_ml), 0) AS g FROM v_incidents i" + fi.where(), fi.params);

        MapSqlParameterSource sp = new MapSqlParameterSource().addValue("site", siteId);
        long openShifts = jdbc.queryForObject("SELECT count(*) FROM v_shifts WHERE status = 'OPEN'" + (siteId != null ? " AND site_id = :site" : ""), sp, Long.class);
        long reporting = jdbc.queryForObject("SELECT count(DISTINCT m.site_id) FROM v_truck_markings m" + f.where(), f.params, Long.class);

        Map<LocalDate, DayPoint> days = new TreeMap<>();
        jdbc.query("""
                SELECT m.registered_at::date AS day, count(*) AS trucks, COALESCE(sum(m.rum_ml), 0) AS rum
                FROM v_truck_markings m""" + f.where() + " GROUP BY 1 ORDER BY 1", f.params, rs -> {
            LocalDate d = rs.getDate("day").toLocalDate();
            days.put(d, new DayPoint(d, rs.getLong("trucks"), rs.getDouble("rum")));
        });
        // fill gaps so charts show empty days
        List<DayPoint> series = new ArrayList<>();
        if (from != null && to != null && from.until(to).getDays() <= 92 && !to.isBefore(from))
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) series.add(days.getOrDefault(d, new DayPoint(d, 0, 0)));
        else series.addAll(days.values());

        return new Overview(((Number) t.get("trucks")).longValue(), ((Number) t.get("completed")).longValue(),
                ((Number) t.get("fuel")).doubleValue(), ((Number) t.get("rum")).doubleValue(),
                ((Number) inc.get("n")).longValue(), ((Number) inc.get("g")).doubleValue(), openShifts, reporting, series);
    }

    public List<SiteStatus> siteStatus() {
        return jdbc.query("""
                SELECT s.id, s.code, s.name, s.site_type, s.active,
                       (SELECT max(e.received_at) FROM synced_entities e WHERE e.source_site_id = s.id) AS last_sync,
                       EXTRACT(EPOCH FROM (now() - (SELECT max(e.received_at) FROM synced_entities e WHERE e.source_site_id = s.id)))::bigint AS last_sync_age,
                       EXISTS (SELECT 1 FROM v_shifts sh WHERE sh.site_id = s.id AND sh.status = 'OPEN') AS shift_open,
                       (SELECT count(*) FROM v_truck_markings m WHERE m.site_id = s.id AND m.status <> 'DELETED' AND m.registered_at >= current_date) AS trucks_today,
                       (SELECT sh.location_name FROM v_shifts sh WHERE sh.site_id = s.id AND sh.location_name IS NOT NULL ORDER BY sh.started_at DESC LIMIT 1) AS location
                FROM marking_sites s ORDER BY s.name""", (rs, n) -> new SiteStatus(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("site_type"), rs.getBoolean("active"),
                ts(rs, "last_sync"), age(rs), rs.getBoolean("shift_open"), rs.getLong("trucks_today"), rs.getString("location")));
    }

    // ---------------------------------------------------------------- markings

    private static final RowMapper<MarkingRow> MARKING = (rs, n) -> new MarkingRow(rs.getObject("id", UUID.class),
            rs.getObject("site_id", UUID.class), rs.getString("site_name"), rs.getString("order_number"),
            rs.getString("truck_number"), rs.getString("trailer_number"), rs.getString("omc_name"), rs.getString("fuel_type"),
            rs.getDouble("declared_volume_l"), rs.getDouble("rum_ml"), rs.getString("status"), rs.getString("rum_type"),
            ts(rs, "registered_at"), ts(rs, "completed_at"), rs.getString("location"));

    private static final String MARKING_FROM = " FROM v_truck_markings m LEFT JOIN marking_sites s ON s.id = m.site_id LEFT JOIN v_shifts sh ON sh.id = m.shift_id";
    private static final String MARKING_COLS = "SELECT m.id, m.site_id, s.name AS site_name, m.order_number, m.truck_number, m.trailer_number, m.omc_name, " +
            "m.fuel_type, m.declared_volume_l, m.rum_ml, m.status, m.rum_type, m.registered_at, m.completed_at, sh.location_name AS location";

    public Page<MarkingRow> markings(UUID siteId, UUID omcId, UUID locationId, String fuel, String status, String q, LocalDate from, LocalDate to, int page, int size) {
        Filter f = new Filter().eq("m.site_id", "site", siteId).eq("m.omc_id", "omc", omcId).eq("sh.location_id", "location", locationId)
                .eq("m.status", "status", status).search("q", q, "m.order_number", "m.truck_number", "m.trailer_number").range("m.registered_at", from, to);
        if (!"DELETED".equals(status)) f.clauses.add("m.status <> 'DELETED'");      // deleted markings are only listed when asked for
        if (fuel != null && !fuel.isBlank()) {          // a truck matches when any of its compartments carries the fuel
            f.clauses.add("EXISTS (SELECT 1 FROM jsonb_array_elements(m.fuel_breakdown) fb WHERE fb ->> 'fuelType' = :fuel)");
            f.params.addValue("fuel", fuel);
        }
        int sz = size(size), pg = Math.max(0, page);
        long total = jdbc.queryForObject("SELECT count(*)" + MARKING_FROM + f.where(), f.params, Long.class);
        f.params.addValue("limit", sz).addValue("offset", (long) pg * sz);
        return new Page<>(jdbc.query(MARKING_COLS + MARKING_FROM + f.where() + " ORDER BY m.registered_at DESC LIMIT :limit OFFSET :offset", f.params, MARKING),
                total, pg, sz);
    }

    public Optional<MarkingDetail> marking(UUID id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        List<MarkingRow> rows = jdbc.query(MARKING_COLS + MARKING_FROM + " WHERE m.id = :id", p, MARKING);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> times = jdbc.queryForMap("SELECT dosed_at, dispensed_at, compartments::text AS comps, doses::text AS doses_json, dose_required_g, dose_measured_g, dose_source FROM v_truck_markings WHERE id = :id", p);
        List<Compartment> comps = new ArrayList<>();
        List<Dose> doses = new ArrayList<>();
        try {
            String json = (String) times.get("comps");
            if (json != null) for (JsonNode c : mapper.readTree(json))
                comps.add(new Compartment(c.path("compartmentNumber").asText(), c.path("capacity").asDouble(), c.path("actualVolume").asDouble(),
                        c.path("fuelType").asText(null), c.path("rumType").asText(null)));
            String dj = (String) times.get("doses_json");
            if (dj != null) for (JsonNode d : mapper.readTree(dj)) {
                String at = d.path("dosedAt").asText(null);
                doses.add(new Dose(d.path("compartment").asText(null), d.path("rumType").asText(null), d.path("requiredG").asDouble(), d.path("measuredG").asDouble(), d.path("source").asText(null),
                        at == null ? null : LocalDateTime.parse(at.length() > 19 ? at.substring(0, 19) : at)));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        List<SignatureInfo> sigs = jdbc.query("""
                SELECT g.id, g.step, COALESCE(u.full_name, g.signed_by::text) AS signer, g.signed_at
                FROM v_signatures g LEFT JOIN app_users u ON u.id = g.signed_by
                WHERE g.entity_type = 'TRUCK_MARKING' AND g.entity_id = :id ORDER BY g.signed_at""", p,
                (rs, n) -> new SignatureInfo(rs.getObject("id", UUID.class), rs.getString("step"), rs.getString("signer"), ts(rs, "signed_at")));
        Timestamp d = (Timestamp) times.get("dosed_at"), s = (Timestamp) times.get("dispensed_at");
        Number required = (Number) times.get("dose_required_g"), weighed = (Number) times.get("dose_measured_g");
        return Optional.of(new MarkingDetail(rows.get(0), d == null ? null : d.toLocalDateTime(), s == null ? null : s.toLocalDateTime(), comps, sigs,
                required == null ? null : required.doubleValue(), weighed == null ? null : weighed.doubleValue(), (String) times.get("dose_source"), doses));
    }

    private static final Map<String, String[]> GROUPS = Map.of(
            "fuel", new String[]{"m.fuel_type", "m.fuel_type"},
            "omc", new String[]{"COALESCE(m.omc_name, '(unknown)')", "COALESCE(m.omc_name, '(unknown)')"},
            "site", new String[]{"m.site_id::text", "COALESCE(s.name, '(unknown)')"},
            "day", new String[]{"to_char(m.registered_at, 'YYYY-MM-DD')", "to_char(m.registered_at, 'YYYY-MM-DD')"});

    public List<SummaryRow> markingSummary(String groupBy, UUID siteId, LocalDate from, LocalDate to) {
        String[] g = GROUPS.get(groupBy);
        if (g == null) throw new IllegalArgumentException("groupBy must be one of " + new TreeSet<>(GROUPS.keySet()));
        Filter f = new Filter().eq("m.site_id", "site", siteId).range("m.registered_at", from, to);
        f.clauses.add("m.status <> 'DELETED'");
        if ("fuel".equals(groupBy)) {      // a truck with two fuels counts under both, each with its own litres and the rum dosed for it
            return jdbc.query("SELECT b ->> 'fuelType' AS k, b ->> 'fuelType' AS label, count(DISTINCT m.id) AS trucks, COALESCE(sum((b ->> 'litres')::numeric), 0) AS fuel, " +
                    "COALESCE(sum((b ->> 'rumG')::numeric), 0) AS rum FROM v_truck_markings m CROSS JOIN LATERAL jsonb_array_elements(m.fuel_breakdown) b" + f.where() +
                    " GROUP BY 1, 2 ORDER BY 2", f.params,
                    (rs, n) -> new SummaryRow(rs.getString("k"), rs.getString("label"), rs.getLong("trucks"), rs.getDouble("fuel"), rs.getDouble("rum")));
        }
        return jdbc.query("SELECT " + g[0] + " AS k, " + g[1] + " AS label, count(*) AS trucks, COALESCE(sum(m.declared_volume_l), 0) AS fuel, " +
                "COALESCE(sum(m.rum_ml), 0) AS rum" + MARKING_FROM + f.where() + " GROUP BY 1, 2 ORDER BY 2", f.params,
                (rs, n) -> new SummaryRow(rs.getString("k"), rs.getString("label"), rs.getLong("trucks"), rs.getDouble("fuel"), rs.getDouble("rum")));
    }

    // ---------------------------------------------------------------- inventory

    public Page<ContainerRow> containers(UUID siteId, String status, String type, String q, int page, int size) {
        Filter f = new Filter().eq("c.site_id", "site", siteId).eq("c.status", "status", status).eq("c.container_type", "type", type)
                .search("q", q, "c.barcode", "c.top_seal", "c.bottom_seal");
        int sz = size(size), pg = Math.max(0, page);
        String from = " FROM v_containers c LEFT JOIN marking_sites s ON s.id = c.site_id";
        long total = jdbc.queryForObject("SELECT count(*)" + from + f.where(), f.params, Long.class);
        f.params.addValue("limit", sz).addValue("offset", (long) pg * sz);
        return new Page<>(jdbc.query("SELECT c.id, c.site_id, s.name AS site_name, c.barcode, c.container_type, c.status, c.capacity_ml, c.volume_ml, c.top_seal, c.bottom_seal" +
                from + f.where() + " ORDER BY s.name, c.container_type, c.barcode LIMIT :limit OFFSET :offset", f.params,
                (rs, n) -> new ContainerRow(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class), rs.getString("site_name"),
                        rs.getString("barcode"), rs.getString("container_type"), rs.getString("status"), rs.getDouble("capacity_ml"), rs.getDouble("volume_ml"),
                        rs.getString("top_seal"), rs.getString("bottom_seal"))),
                total, pg, sz);
    }

    public List<InventoryRow> inventory(UUID siteId) {
        Filter f = new Filter().eq("c.site_id", "site", siteId);
        return jdbc.query("SELECT COALESCE(s.name, '(unknown)') AS site_name, c.container_type, c.status, count(*) AS n, COALESCE(sum(c.volume_ml), 0) AS g " +
                "FROM v_containers c LEFT JOIN marking_sites s ON s.id = c.site_id" + f.where() +
                " GROUP BY 1, 2, 3 ORDER BY 1, 2, 3", f.params,
                (rs, n) -> new InventoryRow(rs.getString("site_name"), rs.getString("container_type"), rs.getString("status"), rs.getLong("n"), rs.getDouble("g")));
    }

    // ---------------------------------------------------------------- incidents, shifts

    public Page<IncidentRow> incidents(UUID siteId, String type, String rumType, LocalDate from, LocalDate to, int page, int size) {
        Filter f = new Filter().eq("i.site_id", "site", siteId).eq("i.incident_type", "type", type).eq("i.rum_type", "rum", rumType).range("i.occurred_at", from, to);
        int sz = size(size), pg = Math.max(0, page);
        String join = " FROM v_incidents i LEFT JOIN marking_sites s ON s.id = i.site_id LEFT JOIN v_containers c ON c.id = i.container_id " +
                "LEFT JOIN app_users u ON u.id = i.reported_by";
        long total = jdbc.queryForObject("SELECT count(*)" + join + f.where(), f.params, Long.class);
        f.params.addValue("limit", sz).addValue("offset", (long) pg * sz);
        return new Page<>(jdbc.query("SELECT i.id, i.site_id, s.name AS site_name, i.incident_type, i.quantity_ml, i.description, c.barcode, " +
                "COALESCE(u.full_name, i.reported_by::text) AS reporter, i.occurred_at, i.rum_type" + join + f.where() +
                " ORDER BY i.occurred_at DESC LIMIT :limit OFFSET :offset", f.params,
                (rs, n) -> new IncidentRow(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class), rs.getString("site_name"),
                        rs.getString("incident_type"), rs.getDouble("quantity_ml"), rs.getString("description"), rs.getString("barcode"),
                        rs.getString("reporter"), ts(rs, "occurred_at"), rs.getString("rum_type"))), total, pg, sz);
    }

    public Page<ShiftRow> shifts(UUID siteId, UUID locationId, String status, LocalDate from, LocalDate to, int page, int size) {
        Filter f = new Filter().eq("h.site_id", "site", siteId).eq("h.location_id", "location", locationId).eq("h.status", "status", status).range("h.started_at", from, to);
        int sz = size(size), pg = Math.max(0, page);
        String join = " FROM v_shifts h LEFT JOIN marking_sites s ON s.id = h.site_id LEFT JOIN app_users u ON u.id = h.supervisor_id";
        long total = jdbc.queryForObject("SELECT count(*)" + join + f.where(), f.params, Long.class);
        f.params.addValue("limit", sz).addValue("offset", (long) pg * sz);
        return new Page<>(jdbc.query("SELECT h.id, h.site_id, s.name AS site_name, COALESCE(u.full_name, h.supervisor_id::text) AS supervisor, " +
                "h.started_at, h.ended_at, h.status, h.opening_ml, h.closing_ml, h.variance_ml, h.location_name" + join + f.where() +
                " ORDER BY h.started_at DESC LIMIT :limit OFFSET :offset", f.params,
                (rs, n) -> new ShiftRow(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class), rs.getString("site_name"),
                        rs.getString("supervisor"), ts(rs, "started_at"), ts(rs, "ended_at"), rs.getString("status"),
                        rs.getDouble("opening_ml"), nullableDouble(rs, "closing_ml"), nullableDouble(rs, "variance_ml"), rs.getString("location_name"))), total, pg, sz);
    }

    // ---------------------------------------------------------------- transfers

    public Page<TransferRow> transfers(UUID siteId, String status, LocalDate from, LocalDate to, int page, int size) {
        Filter f = new Filter().eq("t.status", "status", status).range("t.dispatched_at", from, to);
        if (siteId != null) { f.clauses.add("(t.from_site_id = :site OR t.to_site_id = :site)"); f.params.addValue("site", siteId); }
        int sz = size(size), pg = Math.max(0, page);
        String join = " FROM v_transfers t LEFT JOIN marking_sites a ON a.id = t.from_site_id LEFT JOIN marking_sites b ON b.id = t.to_site_id";
        long total = jdbc.queryForObject("SELECT count(*)" + join + f.where(), f.params, Long.class);
        f.params.addValue("limit", sz).addValue("offset", (long) pg * sz);
        return new Page<>(jdbc.query("""
                SELECT t.id, a.name AS from_site, b.name AS to_site, t.status, t.dispatched_at, t.kind,
                       jsonb_array_length(t.items) AS items,
                       (SELECT count(*) FROM jsonb_array_elements(t.items) i
                         WHERE (i ->> 'received')::boolean IS TRUE OR i ->> 'weightKgAtReceipt' IS NOT NULL) AS received""" +
                join + f.where() + " ORDER BY t.dispatched_at DESC LIMIT :limit OFFSET :offset", f.params, TRANSFER), total, pg, sz);
    }

    private static final RowMapper<TransferRow> TRANSFER = (rs, n) -> new TransferRow(rs.getObject("id", UUID.class),
            rs.getString("from_site"), rs.getString("to_site"), rs.getString("status"), ts(rs, "dispatched_at"),
            rs.getLong("items"), rs.getLong("received"), rs.getString("kind"));

    public Optional<TransferDetail> transfer(UUID id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        List<TransferRow> rows = jdbc.query("""
                SELECT t.id, a.name AS from_site, b.name AS to_site, t.status, t.dispatched_at, t.kind, jsonb_array_length(t.items) AS items,
                       (SELECT count(*) FROM jsonb_array_elements(t.items) i
                         WHERE (i ->> 'received')::boolean IS TRUE OR i ->> 'weightKgAtReceipt' IS NOT NULL) AS received
                FROM v_transfers t LEFT JOIN marking_sites a ON a.id = t.from_site_id LEFT JOIN marking_sites b ON b.id = t.to_site_id
                WHERE t.id = :id""", p, TRANSFER);
        if (rows.isEmpty()) return Optional.empty();
        String json = jdbc.queryForObject("SELECT items::text FROM v_transfers WHERE id = :id", p, String.class);
        List<TransferItem> items = new ArrayList<>();
        try {
            for (JsonNode i : mapper.readTree(json)) {
                // transfers recorded before weighing was dropped say "received" by carrying a receipt weight
                items.add(new TransferItem(i.path("barcode").asText(), i.path("received").asBoolean(false) || i.hasNonNull("weightKgAtReceipt")));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return Optional.of(new TransferDetail(rows.get(0), items));
    }

    // ---------------------------------------------------------------- stock counts

    private static final String COUNT_COLUMNS = """
            SELECT c.id, c.site_id, s.name AS site_name, COALESCE(u.full_name, c.counted_by::text) AS counted_by, c.counted_at, c.comment,
                   c.total_system_g, c.total_counted_g, c.total_variance_g, jsonb_array_length(c.lines) AS containers,
                   (SELECT count(*) FROM jsonb_array_elements(c.lines) l WHERE abs((l ->> 'varianceG')::numeric) >= 1) AS differing, c.kind""";
    private static final String COUNT_JOIN = " FROM v_stock_counts c LEFT JOIN marking_sites s ON s.id = c.site_id LEFT JOIN app_users u ON u.id = c.counted_by";
    private static final RowMapper<StockCountRow> STOCK_COUNT = (rs, n) -> new StockCountRow(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
            rs.getString("site_name"), rs.getString("counted_by"), ts(rs, "counted_at"), rs.getString("comment"), rs.getDouble("total_system_g"),
            rs.getDouble("total_counted_g"), rs.getDouble("total_variance_g"), rs.getLong("containers"), rs.getLong("differing"), rs.getString("kind"));

    public Page<StockCountRow> stockCounts(UUID siteId, String kind, LocalDate from, LocalDate to, int page, int size) {
        Filter f = new Filter().eq("c.site_id", "site", siteId).eq("c.kind", "kind", kind).range("c.counted_at", from, to);
        int sz = size(size), pg = Math.max(0, page);
        long total = jdbc.queryForObject("SELECT count(*)" + COUNT_JOIN + f.where(), f.params, Long.class);
        f.params.addValue("limit", sz).addValue("offset", (long) pg * sz);
        return new Page<>(jdbc.query(COUNT_COLUMNS + COUNT_JOIN + f.where() + " ORDER BY c.counted_at DESC LIMIT :limit OFFSET :offset", f.params, STOCK_COUNT), total, pg, sz);
    }

    public Optional<StockCountDetail> stockCount(UUID id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        List<StockCountRow> rows = jdbc.query(COUNT_COLUMNS + COUNT_JOIN + " WHERE c.id = :id", p, STOCK_COUNT);
        if (rows.isEmpty()) return Optional.empty();
        List<StockCountLine> lines = new ArrayList<>();
        try {
            for (JsonNode l : mapper.readTree(jdbc.queryForObject("SELECT lines::text FROM v_stock_counts WHERE id = :id", p, String.class)))
                lines.add(new StockCountLine(l.path("barcode").asText(), l.path("containerType").asText(null), l.path("rumTypeCode").asText(null),
                        l.path("systemG").asDouble(), l.path("countedG").asDouble(), l.path("varianceG").asDouble(), l.path("source").asText(null), l.path("cause").asText(null), l.path("note").asText(null), l.path("check").asText(null)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return Optional.of(new StockCountDetail(rows.get(0), lines));
    }

    // ---------------------------------------------------------------- signatures

    /** PNG bytes of a stored signature. */
    public Optional<byte[]> signatureImage(UUID id) {
        List<String> b64 = jdbc.queryForList("SELECT payload ->> 'signaturePng' FROM synced_entities WHERE entity_type = 'SIGNATURE' AND entity_id = :id",
                new MapSqlParameterSource("id", id.toString()), String.class);
        return b64.isEmpty() ? Optional.empty() : Optional.of(Base64.getDecoder().decode(b64.get(0)));
    }
}
