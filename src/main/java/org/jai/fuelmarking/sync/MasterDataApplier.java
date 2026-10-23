package org.jai.fuelmarking.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Applies a master-data message from Kafka to the local DB. Master data is owned centrally, so it is written
 * directly (never queued in the outbox) and always overwrites the local copy.
 */
public class MasterDataApplier {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void apply(Connection c, String json) throws SQLException {
        JsonNode msg;
        try { msg = MAPPER.readTree(json); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Malformed master data message"); }
        JsonNode p = msg.path("payload");
        if (!"UPSERT".equals(msg.path("op").asText()) || !p.isObject()) throw new IllegalArgumentException("Unsupported master data message");
        switch (msg.path("type").asText()) {
            case "SITE" -> site(c, p);
            case "OMC" -> omc(c, p);
            case "FUEL_TYPE" -> fuelType(c, p);
            case "RUM_TYPE" -> rumType(c, p);
            case "LOCATION" -> location(c, p);
            case "CONTAINER_TYPE" -> containerType(c, p);
            case "JERRICAN" -> jerrican(c, p);
            case "USER" -> user(c, p);
            default -> throw new IllegalArgumentException("Unknown master data type " + msg.path("type").asText());
        }
    }

    private void site(Connection c, JsonNode p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO marking_sites(id, code, name, site_type, active) VALUES (?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, site_type=EXCLUDED.site_type,
                    active=EXCLUDED.active, updated_at=now()""")) {
            ps.setObject(1, uuid(p, "id")); ps.setString(2, text(p, "code")); ps.setString(3, text(p, "name"));
            ps.setString(4, text(p, "siteType")); ps.setBoolean(5, p.path("active").asBoolean(true));
            ps.executeUpdate();
        }
    }

    private void omc(Connection c, JsonNode p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO oil_marketing_companies(id, code, name, active) VALUES (?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, active=EXCLUDED.active, updated_at=now()""")) {
            ps.setObject(1, uuid(p, "id")); ps.setString(2, text(p, "code")); ps.setString(3, text(p, "name"));
            ps.setBoolean(4, p.path("active").asBoolean(true));
            ps.executeUpdate();
        }
    }

    /** A fuel type: id, code, name, active - and the rum type it is marked with (kept as it was if the message carries none). */
    private void fuelType(Connection c, JsonNode p) throws SQLException {
        String rum = p.path("rumTypeCode").isTextual() && !p.path("rumTypeCode").asText().isBlank() ? p.path("rumTypeCode").asText().trim() : null;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO fuel_types(id, code, name, rum_type_code, active) VALUES (?,?,?,?,?) " +
                "ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, rum_type_code=COALESCE(EXCLUDED.rum_type_code, fuel_types.rum_type_code), " +
                "active=EXCLUDED.active, updated_at=now()")) {
            ps.setObject(1, uuid(p, "id")); ps.setString(2, text(p, "code")); ps.setString(3, text(p, "name"));
            ps.setString(4, rum);
            ps.setBoolean(5, p.path("active").asBoolean(true));
            ps.executeUpdate();
        }
    }

    private void location(Connection c, JsonNode p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO locations(id, code, name, active) VALUES (?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, active=EXCLUDED.active, updated_at=now()""")) {
            ps.setObject(1, uuid(p, "id")); ps.setString(2, text(p, "code")); ps.setString(3, text(p, "name"));
            ps.setBoolean(4, p.path("active").asBoolean(true));
            ps.executeUpdate();
        }
    }

    private void rumType(Connection c, JsonNode p) throws SQLException {
        String color = p.path("color").asText("#808080");
        if (!color.matches("#[0-9A-Fa-f]{6}")) color = "#808080";
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO rum_types(id, code, name, color, active) VALUES (?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, color=EXCLUDED.color, active=EXCLUDED.active, updated_at=now()""")) {
            ps.setObject(1, uuid(p, "id")); ps.setString(2, text(p, "code")); ps.setString(3, text(p, "name")); ps.setString(4, color.toUpperCase());
            ps.setBoolean(5, p.path("active").asBoolean(true));
            ps.executeUpdate();
        }
    }

    private void containerType(Connection c, JsonNode p) throws SQLException {
        String category = text(p, "category");
        if (!category.equals("BULK") && !category.equals("JERRICAN")) throw new IllegalArgumentException("Bad category " + category);
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO container_types(id, code, name, category, seals, default_capacity_g, active) VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, category=EXCLUDED.category, seals=EXCLUDED.seals,
                    default_capacity_g=EXCLUDED.default_capacity_g, active=EXCLUDED.active, updated_at=now()""")) {
            ps.setObject(1, uuid(p, "id")); ps.setString(2, text(p, "code")); ps.setString(3, text(p, "name")); ps.setString(4, category);
            ps.setInt(5, p.path("seals").asInt());
            if (p.hasNonNull("defaultCapacityG")) ps.setDouble(6, p.get("defaultCapacityG").asDouble()); else ps.setNull(6, java.sql.Types.NUMERIC);
            ps.setBoolean(7, p.path("active").asBoolean(true));
            ps.executeUpdate();
        }
    }

    private void jerrican(Connection c, JsonNode p) throws SQLException {
        String typeCode = text(p, "containerTypeCode");
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM container_types WHERE code = ? AND category = 'JERRICAN'")) {
            ps.setString(1, typeCode);
            try (var rs = ps.executeQuery()) { if (!rs.next()) throw new MissingDependencyException("Container type " + typeCode + " not synced yet"); }
        }
        String rum = text(p, "rumTypeCode");
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM rum_types WHERE code = ?")) {
            ps.setString(1, rum);
            try (var rs = ps.executeQuery()) { if (!rs.next()) throw new MissingDependencyException("Rum type " + rum + " not synced yet"); }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO registered_jerricans(id, barcode, container_type_code, capacity_g, rum_type_code, active) VALUES (?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET barcode=EXCLUDED.barcode, container_type_code=EXCLUDED.container_type_code, capacity_g=EXCLUDED.capacity_g,
                    rum_type_code=EXCLUDED.rum_type_code, active=EXCLUDED.active, updated_at=now()""")) {
            ps.setObject(1, uuid(p, "id")); ps.setString(2, text(p, "barcode")); ps.setString(3, typeCode);
            ps.setDouble(4, p.path("capacityG").asDouble()); ps.setString(5, rum); ps.setBoolean(6, p.path("active").asBoolean(true));
            ps.executeUpdate();
        }
    }

    private void user(Connection c, JsonNode p) throws SQLException {
        UUID siteId = p.hasNonNull("siteId") ? uuid(p, "siteId") : null;
        if (siteId != null && !exists(c, "marking_sites", siteId))
            throw new MissingDependencyException("Site " + siteId + " not synced yet");
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO app_users(id, username, full_name, role, site_id, active, pin_hash) VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET username=EXCLUDED.username, full_name=EXCLUDED.full_name, role=EXCLUDED.role,
                    site_id=EXCLUDED.site_id, active=EXCLUDED.active, pin_hash=EXCLUDED.pin_hash,
                    -- an administrator's PIN reset also clears any lockout
                    failed_pin_attempts = CASE WHEN app_users.pin_hash IS DISTINCT FROM EXCLUDED.pin_hash THEN 0 ELSE app_users.failed_pin_attempts END,
                    locked_until = CASE WHEN app_users.pin_hash IS DISTINCT FROM EXCLUDED.pin_hash THEN NULL ELSE app_users.locked_until END""")) {
            ps.setObject(1, uuid(p, "id")); ps.setString(2, text(p, "username")); ps.setString(3, text(p, "fullName"));
            ps.setString(4, text(p, "role")); ps.setObject(5, siteId); ps.setBoolean(6, p.path("active").asBoolean(true));
            ps.setString(7, p.hasNonNull("pinHash") ? p.get("pinHash").asText() : null);
            ps.executeUpdate();
        }
    }

    static boolean exists(Connection c, String table, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    static UUID uuid(JsonNode p, String field) {
        try { return UUID.fromString(p.path(field).asText()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Bad " + field); }
    }

    static String text(JsonNode p, String field) {
        String v = p.path(field).asText(null);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return v;
    }
}
