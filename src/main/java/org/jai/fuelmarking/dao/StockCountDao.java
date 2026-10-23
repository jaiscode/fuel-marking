package org.jai.fuelmarking.dao;

import org.jai.fuelmarking.model.Records.StockCount;
import org.jai.fuelmarking.model.Records.StockCountLine;

import java.sql.*;
import java.util.*;

public class StockCountDao {

    public void insert(Connection c, StockCount sc, Map<UUID, StockCountLine> linesByContainer) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO stock_counts(id, site_id, counted_by, counted_at, comment, total_system_g, total_counted_g, kind, shift_id) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, sc.id()); ps.setObject(2, sc.siteId()); ps.setObject(3, sc.countedBy());
            ps.setTimestamp(4, Timestamp.valueOf(sc.countedAt())); ps.setString(5, sc.comment());
            ps.setDouble(6, sc.totalSystemG()); ps.setDouble(7, sc.totalCountedG());
            ps.setString(8, sc.kind()); ps.setObject(9, sc.shiftId());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO stock_count_lines(stock_count_id, container_id, barcode, container_type, rum_type_code, system_g, counted_g, source, cause, note, incident_id, check_result)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""")) {
            for (var e : linesByContainer.entrySet()) {
                StockCountLine l = e.getValue();
                ps.setObject(1, sc.id()); ps.setObject(2, e.getKey()); ps.setString(3, l.barcode()); ps.setString(4, l.containerType());
                ps.setString(5, l.rumTypeCode()); ps.setDouble(6, l.systemG()); ps.setDouble(7, l.countedG()); ps.setString(8, l.source());
                ps.setString(9, l.cause()); ps.setString(10, l.note()); ps.setObject(11, l.incidentId()); ps.setString(12, l.checkResult());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public List<StockCount> history(Connection c, UUID siteId, String kind, int limit) throws SQLException {
        List<StockCount> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, site_id, counted_by, counted_at, comment, total_system_g, total_counted_g, kind, shift_id FROM stock_counts " +
                "WHERE site_id = ? AND (?::varchar IS NULL OR kind = ?) ORDER BY counted_at DESC LIMIT ?")) {
            ps.setObject(1, siteId); ps.setString(2, kind); ps.setString(3, kind); ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new StockCount(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getTimestamp(4).toLocalDateTime(), rs.getString(5), rs.getDouble(6), rs.getDouble(7), rs.getString(8), rs.getObject(9, UUID.class)));
            }
        }
        return out;
    }

    public List<StockCountLine> lines(Connection c, UUID stockCountId) throws SQLException {
        List<StockCountLine> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT barcode, container_type, rum_type_code, system_g, counted_g, source, cause, note, incident_id, check_result FROM stock_count_lines WHERE stock_count_id = ? ORDER BY barcode")) {
            ps.setObject(1, stockCountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new StockCountLine(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getObject(9, UUID.class), rs.getString(10)));
            }
        }
        return out;
    }

    /** Queues the count, with its lines, for head office. */
    public void publish(Connection c, StockCount sc) throws SQLException {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (StockCountLine l : lines(c, sc.id())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("barcode", l.barcode()); m.put("containerType", l.containerType()); m.put("rumTypeCode", l.rumTypeCode());
            m.put("systemG", l.systemG()); m.put("countedG", l.countedG()); m.put("varianceG", l.varianceG()); m.put("source", l.source());
            m.put("cause", l.cause()); m.put("note", l.note()); m.put("incidentId", l.incidentId()); m.put("check", l.checkResult());
            lines.add(m);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", sc.id()); p.put("siteId", sc.siteId()); p.put("countedBy", sc.countedBy()); p.put("countedAt", sc.countedAt());
        p.put("comment", sc.comment()); p.put("totalSystemG", sc.totalSystemG()); p.put("totalCountedG", sc.totalCountedG());
        p.put("totalVarianceG", sc.totalVarianceG()); p.put("kind", sc.kind()); p.put("shiftId", sc.shiftId()); p.put("lines", lines);
        Outbox.upsert(c, "STOCK_COUNT", sc.id(), p);
    }
}
