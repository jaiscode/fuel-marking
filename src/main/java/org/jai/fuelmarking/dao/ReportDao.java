package org.jai.fuelmarking.dao;

import org.jai.fuelmarking.model.Records.ReportTable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Read-only report queries. Column aliases become the report headers. */
public class ReportDao {

    public ReportTable inventory(Connection c, UUID siteId) throws SQLException {
        return run(c, """
                SELECT container_type AS "Container type", status AS "Status", count(*) AS "Containers",
                       sum(current_volume_ml) / 1000.0 AS "Rum (kg)", sum(capacity_ml) / 1000.0 AS "Capacity (kg)"
                FROM containers WHERE site_id = ? GROUP BY container_type, status ORDER BY container_type, status""", siteId);
    }

    public ReportTable markingByFuel(Connection c, UUID siteId, LocalDateTime from, LocalDateTime to) throws SQLException {
        // per fuel, from the compartments: a truck with two fuels counts under both, each with its own litres and the rum weighed for its compartments.
        // A truck recorded before compartments carried a fuel counts as one fuel (its dose is under "ALL").
        return run(c, """
                WITH part AS (
                    SELECT m.id AS mid, m.status, COALESCE(cd.fuel_type_code, m.fuel_type) AS fuel, COALESCE(cd.actual_volume, m.declared_volume) AS litres,
                           COALESCE((SELECT sum(d.measured_g) FROM marking_doses d
                                     WHERE d.truck_marking_id = m.id AND d.compartment_number = COALESCE(cd.compartment_number, 'ALL')), 0) AS rum_g
                    FROM truck_markings m LEFT JOIN compartment_details cd ON cd.truck_marking_id = m.id
                    WHERE m.site_id = ? AND m.status <> 'DELETED' AND m.registered_time >= ? AND m.registered_time < ?)
                SELECT fuel AS "Fuel type", count(DISTINCT mid) AS "Trucks", count(DISTINCT mid) FILTER (WHERE status = 'COMPLETED') AS "Completed",
                       sum(litres) AS "Fuel marked (L)", COALESCE(sum(rum_g), 0) AS "Rum dosed (g)"
                FROM part GROUP BY fuel ORDER BY fuel""", siteId, from, to);
    }

    public ReportTable markingByOmc(Connection c, UUID siteId, LocalDateTime from, LocalDateTime to) throws SQLException {
        return run(c, """
                SELECT oil_marketing_company AS "Oil marketing company", count(*) AS "Trucks",
                       count(*) FILTER (WHERE status='COMPLETED') AS "Completed",
                       sum(declared_volume) AS "Fuel marked (L)", COALESCE(sum(COALESCE(dose_measured_g, dose_calculated_ml)),0) AS "Rum dosed (g)"
                FROM truck_markings WHERE site_id = ? AND status <> 'DELETED' AND registered_time >= ? AND registered_time < ?
                GROUP BY oil_marketing_company ORDER BY 1""", siteId, from, to);
    }

    public ReportTable markingDetail(Connection c, UUID siteId, LocalDateTime from, LocalDateTime to) throws SQLException {
        return run(c, """
                SELECT m.order_number AS "Order", m.registered_time AS "Registered", m.truck_number AS "Truck", m.trailer_number AS "Trailer / wagon",
                       m.oil_marketing_company AS "OMC", m.fuel_type AS "Fuel", m.declared_volume AS "Volume (L)",
                       m.rum_type AS "Rum type", COALESCE(m.dose_calculated_ml,0) AS "Rum required (g)",
                       m.dose_measured_g AS "Rum dosed (g)", m.dose_source AS "Dose source", m.status AS "Status",
                       r.full_name AS "Registered by", d.full_name AS "Dosed by", p.full_name AS "Dispensed by", v.full_name AS "Verified by"
                FROM truck_markings m
                LEFT JOIN app_users r ON r.id = m.registered_by LEFT JOIN app_users d ON d.id = m.dosed_by
                LEFT JOIN app_users p ON p.id = m.dispensed_by LEFT JOIN app_users v ON v.id = m.verified_by
                WHERE m.site_id = ? AND m.status <> 'DELETED' AND m.registered_time >= ? AND m.registered_time < ? ORDER BY m.registered_time""", siteId, from, to);
    }

    public ReportTable incidents(Connection c, UUID siteId, LocalDateTime from, LocalDateTime to) throws SQLException {
        return run(c, """
                SELECT i.occurred_at AS "When", i.incident_type AS "Type", i.quantity_ml AS "Quantity (g)",
                       k.barcode AS "Container", i.rum_type_code AS "Rum type", u.full_name AS "Reported by", i.description AS "Description"
                FROM incidents i LEFT JOIN containers k ON k.id = i.container_id JOIN app_users u ON u.id = i.reported_by
                WHERE i.site_id = ? AND i.occurred_at >= ? AND i.occurred_at < ? ORDER BY i.occurred_at""", siteId, from, to);
    }

    public ReportTable transfers(Connection c, UUID siteId, LocalDateTime from, LocalDateTime to) throws SQLException {
        return run(c, """
                SELECT t.dispatched_at AS "Dispatched", fs.name AS "From", ts.name AS "To", t.status AS "Status",
                       k.barcode AS "Container", k.container_type AS "Type", i.received_at AS "Received"
                FROM transfers t JOIN marking_sites fs ON fs.id = t.from_site_id JOIN marking_sites ts ON ts.id = t.to_site_id
                JOIN transfer_items i ON i.transfer_id = t.id JOIN containers k ON k.id = i.container_id
                WHERE (t.from_site_id = ? OR t.to_site_id = ?) AND t.dispatched_at >= ? AND t.dispatched_at < ?
                ORDER BY t.dispatched_at, k.barcode""", siteId, siteId, from, to);
    }

    public ReportTable stockCounts(Connection c, UUID siteId, LocalDateTime from, LocalDateTime to) throws SQLException {
        return run(c, """
                SELECT s.counted_at AS "Counted", u.full_name AS "Counted by", l.barcode AS "Container", l.container_type AS "Type",
                       l.rum_type_code AS "Rum type", l.system_g / 1000.0 AS "System (kg)", l.counted_g / 1000.0 AS "Counted (kg)",
                       (l.counted_g - l.system_g) / 1000.0 AS "Difference (kg)", l.source AS "Source", s.comment AS "Comment"
                FROM stock_counts s JOIN stock_count_lines l ON l.stock_count_id = s.id JOIN app_users u ON u.id = s.counted_by
                WHERE s.site_id = ? AND s.counted_at >= ? AND s.counted_at < ? ORDER BY s.counted_at, l.barcode""", siteId, from, to);
    }

    private ReportTable run(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int n = 0; n < params.length; n++) {
                Object p = params[n];
                ps.setObject(n + 1, p instanceof LocalDateTime t ? Timestamp.valueOf(t) : p);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> headers = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) headers.add(md.getColumnLabel(i));
                List<List<String>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.add(format(rs.getObject(i)));
                    rows.add(row);
                }
                return new ReportTable(headers, rows);
            }
        }
    }

    private static String format(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal d) return d.setScale(2, RoundingMode.HALF_UP).toPlainString();
        if (v instanceof Timestamp t) return t.toLocalDateTime().withNano(0).toString().replace('T', ' ');
        return v.toString();
    }
}
