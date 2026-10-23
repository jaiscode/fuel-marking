package org.jai.fuelmarking.dao;

import org.jai.fuelmarking.model.Records.Incident;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

public class IncidentDao {
    public Incident insert(Connection c, UUID siteId, UUID shiftId, UUID containerId, String type, double qtyMl,
                           String description, UUID reportedBy, String rumTypeCode) throws SQLException {
        Incident i = new Incident(UUID.randomUUID(), siteId, shiftId, containerId, type, qtyMl, description,
                reportedBy, LocalDateTime.now(), rumTypeCode);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO incidents(id, site_id, shift_id, container_id, incident_type, quantity_ml, description, reported_by, occurred_at, rum_type_code) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, i.id()); ps.setObject(2, siteId); ps.setObject(3, shiftId); ps.setObject(4, containerId);
            ps.setString(5, type); ps.setDouble(6, qtyMl); ps.setString(7, description); ps.setObject(8, reportedBy);
            ps.setTimestamp(9, Timestamp.valueOf(i.occurredAt()));
            ps.setString(10, rumTypeCode);
            ps.executeUpdate();
        }
        Outbox.upsert(c, "INCIDENT", i.id(), i);
        return i;
    }

    public List<Incident> listBySite(Connection c, UUID siteId, LocalDateTime from, LocalDateTime to) throws SQLException {
        List<Incident> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, site_id, shift_id, container_id, incident_type, quantity_ml, description, reported_by, occurred_at, rum_type_code " +
                "FROM incidents WHERE site_id = ? AND occurred_at BETWEEN ? AND ? ORDER BY occurred_at DESC")) {
            ps.setObject(1, siteId); ps.setTimestamp(2, Timestamp.valueOf(from)); ps.setTimestamp(3, Timestamp.valueOf(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Incident(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getDouble(6),
                        rs.getString(7), rs.getObject(8, UUID.class), rs.getTimestamp(9).toLocalDateTime(), rs.getString(10)));
            }
        }
        return out;
    }
}
