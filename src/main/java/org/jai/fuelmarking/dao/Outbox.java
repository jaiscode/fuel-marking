package org.jai.fuelmarking.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Queues a change for sync to the central backend, inside the caller's transaction. */
public final class Outbox {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Outbox() {}

    public static void upsert(Connection c, String entityType, Object entityId, Object payload) throws SQLException {
        enqueue(c, entityType, entityId, "UPSERT", payload);
    }

    private static void enqueue(Connection c, String type, Object id, String op, Object payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sync_outbox(entity_type, entity_id, operation, payload) VALUES (?,?,?,?::jsonb)")) {
            ps.setString(1, type);
            ps.setString(2, String.valueOf(id));
            ps.setString(3, op);
            ps.setString(4, MAPPER.writeValueAsString(payload));
            ps.executeUpdate();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("Cannot serialize outbox payload", e);
        }
    }
}
