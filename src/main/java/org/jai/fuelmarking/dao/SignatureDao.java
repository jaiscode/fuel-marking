package org.jai.fuelmarking.dao;

import java.sql.*;
import java.util.Map;
import java.util.UUID;

public class SignatureDao {
    public void save(Connection c, String entityType, UUID entityId, String step, UUID signedBy, byte[] png) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO signatures(id, entity_type, entity_id, step, signed_by, signature_png) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, id); ps.setString(2, entityType); ps.setObject(3, entityId); ps.setString(4, step);
            ps.setObject(5, signedBy); ps.setBytes(6, png);
            ps.executeUpdate();
        }
        Outbox.upsert(c, "SIGNATURE", id, Map.of("id", id, "entityType", entityType, "entityId", entityId,
                "step", step, "signedBy", signedBy, "signaturePng", java.util.Base64.getEncoder().encodeToString(png)));
    }
}
