package org.jai.fuelmarking.sync;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.jai.fuelmarking.sync.MasterDataApplier.exists;
import static org.jai.fuelmarking.sync.MasterDataApplier.text;
import static org.jai.fuelmarking.sync.MasterDataApplier.uuid;

/**
 * Applies transfers (and the containers they mention) pulled from the central backend. Writes straight to the
 * tables - never through the outbox - so pulled data is not pushed back.
 */
public class InboundApplier {

    /**
     * A pulled container is only taken if we do not have it yet, or we do not hold it: we handed it over (IN_TRANSIT), or our copy says it is at
     * another site. Once a container is in our custody the local copy is authoritative and must not be overwritten by an older central version.
     * (Taking it when it is not ours matters: a site that receives a container must know what is really in it and which seals it carries,
     * and must not pass a stale local copy on to head office.)
     */
    public void container(Connection c, JsonNode p) throws SQLException {
        String barcode = text(p, "barcode");
        String localStatus = null;
        UUID localSite = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT status, site_id FROM containers WHERE barcode = ?")) {
            ps.setString(1, barcode);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { localStatus = rs.getString(1); localSite = rs.getObject(2, UUID.class); } }
        }
        String own = org.jai.fuelmarking.config.YamlConfigurationLoader.Sync.getSiteId();
        boolean heldElsewhere = localStatus != null && own != null && !own.isBlank() && localSite != null && !localSite.toString().equalsIgnoreCase(own.trim());
        UUID siteId = p.hasNonNull("siteId") ? uuid(p, "siteId") : null;
        if (siteId != null && !exists(c, "marking_sites", siteId)) throw new MissingDependencyException("Site " + siteId + " not synced yet");
        UUID source = p.hasNonNull("sourceContainerId") ? uuid(p, "sourceContainerId") : null;
        if (source != null && !exists(c, "containers", source)) source = null; // lineage is informational only

        if (localStatus == null) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO containers(id, barcode, container_type, capacity_ml, current_volume_ml, top_seal, bottom_seal,
                                           status, site_id, source_container_id, rum_type_code, received_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?, now())""")) {
                ps.setObject(1, uuid(p, "id")); ps.setString(2, barcode); ps.setString(3, text(p, "type"));
                ps.setDouble(4, p.path("capacityMl").asDouble()); ps.setDouble(5, p.path("currentVolumeMl").asDouble());
                ps.setString(6, p.path("topSeal").asText(null)); ps.setString(7, p.path("bottomSeal").asText(null));
                ps.setString(8, text(p, "status")); ps.setObject(9, siteId); ps.setObject(10, source);
                ps.setString(11, p.path("rumTypeCode").asText(null));
                ps.executeUpdate();
            }
        } else if ("IN_TRANSIT".equals(localStatus) || heldElsewhere) {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE containers SET current_volume_ml=?, status=?, site_id=?, top_seal=?, bottom_seal=?,
                        rum_type_code=COALESCE(?, rum_type_code), updated_at=now()
                    WHERE barcode=?""")) {
                ps.setDouble(1, p.path("currentVolumeMl").asDouble()); ps.setString(2, text(p, "status")); ps.setObject(3, siteId);
                ps.setString(4, p.path("topSeal").asText(null)); ps.setString(5, p.path("bottomSeal").asText(null));
                ps.setString(6, p.path("rumTypeCode").asText(null)); ps.setString(7, barcode);
                ps.executeUpdate();
            }
        }
    }

    public void transfer(Connection c, JsonNode payload) throws SQLException {
        JsonNode t = payload.path("transfer");
        UUID id = uuid(t, "id"), from = uuid(t, "fromSiteId"), to = uuid(t, "toSiteId");
        for (UUID s : new UUID[]{from, to})
            if (!exists(c, "marking_sites", s)) throw new MissingDependencyException("Site " + s + " not synced yet");
        String status = text(t, "status");

        if (!exists(c, "transfers", id)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transfers(id, from_site_id, to_site_id, status, dispatched_at, kind) VALUES (?,?,?,?,?,?)")) {
                ps.setObject(1, id); ps.setObject(2, from); ps.setObject(3, to); ps.setString(4, status);
                ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.parse(text(t, "dispatchedAt"))));
                // supplies out of a warehouse, or finished jerricans sent back to one (transfers from before returns existed carry no kind)
                ps.setString(6, "RETURN".equals(t.path("kind").asText("SUPPLY")) ? "RETURN" : "SUPPLY");
                ps.executeUpdate();
            }
        } else if ("RECEIVED".equals(status)) { // never move a transfer backwards
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transfers SET status='RECEIVED', received_at=COALESCE(received_at, now()) WHERE id=?")) {
                ps.setObject(1, id);
                ps.executeUpdate();
            }
        }

        for (JsonNode item : payload.path("items")) {
            UUID containerId = containerId(c, text(item, "barcode"));
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO transfer_items(transfer_id, container_id, received_at) VALUES (?,?, CASE WHEN ? THEN now() END)
                    ON CONFLICT (transfer_id, container_id) DO UPDATE SET
                        received_at = COALESCE(transfer_items.received_at, EXCLUDED.received_at)""")) {
                ps.setObject(1, id); ps.setObject(2, containerId);
                // events recorded before weighing was dropped say "received" by carrying a receipt weight
                ps.setBoolean(3, item.path("received").asBoolean(false) || item.hasNonNull("weightKgAtReceipt"));
                ps.executeUpdate();
            }
        }
    }

    private static UUID containerId(Connection c, String barcode) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM containers WHERE barcode = ?")) {
            ps.setString(1, barcode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new MissingDependencyException("Container " + barcode + " not synced yet");
                return rs.getObject(1, UUID.class);
            }
        }
    }

}
