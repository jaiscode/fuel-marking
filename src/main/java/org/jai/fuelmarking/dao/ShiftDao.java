package org.jai.fuelmarking.dao;

import org.jai.fuelmarking.model.Records.Shift;

import java.sql.*;
import java.util.*;

public class ShiftDao {
    private static final String COLS =
            "id, site_id, supervisor_id, started_at, ended_at, status, opening_rum_ml, closing_rum_ml, reconciliation_variance_ml";

    private Shift map(ResultSet rs) throws SQLException {
        Timestamp end = rs.getTimestamp(5);
        return new Shift(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getTimestamp(4).toLocalDateTime(), end == null ? null : end.toLocalDateTime(), rs.getString(6),
                rs.getDouble(7), nullableDouble(rs, 8), nullableDouble(rs, 9));
    }

    private static Double nullableDouble(ResultSet rs, int col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    public Optional<Shift> findOpen(Connection c, UUID siteId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLS + " FROM shifts WHERE site_id = ? AND status = 'OPEN'")) {
            ps.setObject(1, siteId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        }
    }

    public Shift insert(Connection c, UUID siteId, UUID supervisorId, double openingRumMl) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO shifts(id, site_id, supervisor_id, opening_rum_ml) VALUES (?,?,?,?)")) {
            ps.setObject(1, id); ps.setObject(2, siteId); ps.setObject(3, supervisorId); ps.setDouble(4, openingRumMl);
            ps.executeUpdate();
        }
        return publish(c, id);
    }

    public Shift close(Connection c, UUID id, double closingMl, double varianceMl, String notes) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE shifts SET status='CLOSED', ended_at=now(), closing_rum_ml=?, reconciliation_variance_ml=?, notes=? WHERE id=?")) {
            ps.setDouble(1, closingMl); ps.setDouble(2, varianceMl); ps.setString(3, notes); ps.setObject(4, id);
            ps.executeUpdate();
        }
        return publish(c, id);
    }

    private Shift publish(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM shifts WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Shift s = map(rs);
                Outbox.upsert(c, "SHIFT", id, s);
                return s;
            }
        }
    }
}
