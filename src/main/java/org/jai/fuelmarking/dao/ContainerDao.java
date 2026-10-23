package org.jai.fuelmarking.dao;

import org.jai.fuelmarking.model.Records.ContainerType;
import org.jai.fuelmarking.model.Records.Container;

import java.sql.*;
import java.util.*;

public class ContainerDao {
    private static final String COLS =
            "id, barcode, container_type, capacity_ml, current_volume_ml, top_seal, bottom_seal, status, site_id, source_container_id, rum_type_code";
    /** The same columns, qualified, with the container type's own columns after them (a container always has a known type). */
    private static final String SELECT =
            "SELECT k.id, k.barcode, k.container_type, k.capacity_ml, k.current_volume_ml, k.top_seal, k.bottom_seal, k.status, k.site_id, " +
            "k.source_container_id, k.rum_type_code, t.name, t.category, t.seals, t.default_capacity_g " +
            "FROM containers k JOIN container_types t ON t.code = k.container_type ";

    private Container map(ResultSet rs) throws SQLException {
        return new Container(rs.getObject(1, UUID.class), rs.getString(2),
                new ContainerType(rs.getString(3), rs.getString(12), rs.getString(13), rs.getInt(14), rs.getObject(15) == null ? null : rs.getDouble(15)),
                rs.getDouble(4), rs.getDouble(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getObject(9, UUID.class), rs.getObject(10, UUID.class), rs.getString(11));
    }

    public Optional<Container> findByBarcode(Connection c, String barcode, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                SELECT + "WHERE k.barcode = ?" + (lock ? " FOR UPDATE OF k" : ""))) {
            ps.setString(1, barcode);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        }
    }

    public Optional<Container> findById(Connection c, UUID id, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                SELECT + "WHERE k.id = ?" + (lock ? " FOR UPDATE OF k" : ""))) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        }
    }

    public List<Container> listBySiteAndStatus(Connection c, UUID siteId, String status) throws SQLException {
        List<Container> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                SELECT + "WHERE k.site_id = ? AND k.status = ? ORDER BY k.barcode")) {
            ps.setObject(1, siteId); ps.setString(2, status);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
        }
        return out;
    }

    public List<Container> listBySite(Connection c, UUID siteId) throws SQLException {
        List<Container> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                SELECT + "WHERE k.site_id = ? ORDER BY k.container_type, k.barcode")) {
            ps.setObject(1, siteId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
        }
        return out;
    }

    public void insert(Connection c, Container k) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO containers(" + COLS + ", received_at) VALUES (?,?,?,?,?,?,?,?,?,?,?, now())")) {
            ps.setObject(1, k.id()); ps.setString(2, k.barcode()); ps.setString(3, k.type().code());
            ps.setDouble(4, k.capacityMl()); ps.setDouble(5, k.currentVolumeMl());
            ps.setString(6, k.topSeal()); ps.setString(7, k.bottomSeal()); ps.setString(8, k.status());
            ps.setObject(9, k.siteId()); ps.setObject(10, k.sourceContainerId()); ps.setString(11, k.rumTypeCode());
            ps.executeUpdate();
        }
        Outbox.upsert(c, "CONTAINER", k.id(), k);
    }

    public void markOpened(Connection c, UUID id, UUID openedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE containers SET status='OPENED', opened_at=now(), volume_at_open_ml=current_volume_ml, opened_by=?, updated_at=now() WHERE id=?")) {
            ps.setObject(1, openedBy); ps.setObject(2, id);
            ps.executeUpdate();
        }
        recordEvent(c, id, "OPEN");
        publish(c, id);
    }

    /** Notes in the ledger that the container was opened / closed back, with the rum it holds right now. */
    private void recordEvent(Connection c, UUID id, String type) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO container_events(container_id, site_id, event_type, volume_g) SELECT id, site_id, ?, current_volume_ml FROM containers WHERE id = ?")) {
            ps.setString(1, type); ps.setObject(2, id);
            ps.executeUpdate();
        }
    }

    /** Records which bulk container a jerrican was filled from (traceability back to the IBC/drum). */
    public void setSource(Connection c, UUID id, UUID sourceId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE containers SET source_container_id=?, updated_at=now() WHERE id=?")) {
            ps.setObject(1, sourceId); ps.setObject(2, id);
            ps.executeUpdate();
        }
        publish(c, id);
    }

    /** A refilled jerrican: weighed contents, the seals put on it, the bulk container it came from. Back in stock as SEALED. */
    public void markFilled(Connection c, UUID id, double volume, String topSeal, String bottomSeal, UUID sourceId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE containers SET current_volume_ml=?, top_seal=?, bottom_seal=?, source_container_id=?, status='SEALED', updated_at=now() WHERE id=?")) {
            ps.setDouble(1, volume); ps.setString(2, topSeal); ps.setString(3, bottomSeal); ps.setObject(4, sourceId); ps.setObject(5, id);
            ps.executeUpdate();
        }
        publish(c, id);
    }

    /** An opened jerrican that still holds rum, closed back at the end of a shift with new seals: SEALED again, contents unchanged. */
    public void markClosedBack(Connection c, UUID id, String topSeal, String bottomSeal) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE containers SET top_seal=?, bottom_seal=?, status='SEALED', updated_at=now() WHERE id=?")) {
            ps.setString(1, topSeal); ps.setString(2, bottomSeal); ps.setObject(3, id);
            ps.executeUpdate();
        }
        recordEvent(c, id, "CLOSE_BACK");
        publish(c, id);
    }

    /** The container (other than the given one) that already carries this seal number, if any. */
    public Optional<String> sealUsedBy(Connection c, String seal, UUID exceptId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT barcode FROM containers WHERE (top_seal = ? OR bottom_seal = ?) AND id <> ? LIMIT 1")) {
            ps.setString(1, seal); ps.setString(2, seal); ps.setObject(3, exceptId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(); }
        }
    }

    public void setVolume(Connection c, UUID id, double volumeMl) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE containers SET current_volume_ml=?, status=CASE WHEN ?<=0 THEN 'EMPTY' ELSE status END, updated_at=now() WHERE id=?")) {
            ps.setDouble(1, volumeMl); ps.setDouble(2, volumeMl); ps.setObject(3, id);
            ps.executeUpdate();
        }
        if (volumeMl <= 0) discardJerricanSeals(c, id);       // a jerrican that is used up loses its seals
        publish(c, id);
    }

    /** A sealed jerrican that could not be found at a count: no rum is assumed in it any more, and it can no longer be opened, refilled or sent anywhere. */
    public void markMissing(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE containers SET status = 'MISSING', current_volume_ml = 0, updated_at = now() WHERE id = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
        publish(c, id);
    }

    /** Remembers the seal numbers now on the container as used and discarded, so none of them can ever go on a container again. */
    public void rememberSeals(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO discarded_seals(seal, container_id)
                SELECT s, ? FROM (SELECT top_seal AS s FROM containers WHERE id = ? AND container_type LIKE 'JERRICAN%' UNION SELECT bottom_seal FROM containers WHERE id = ? AND container_type LIKE 'JERRICAN%') x
                WHERE s IS NOT NULL ON CONFLICT DO NOTHING""")) {
            ps.setObject(1, id); ps.setObject(2, id); ps.setObject(3, id);
            ps.executeUpdate();
        }
    }

    /** An emptied jerrican has no seals: they are discarded (remembered) and it is sealed again when it is refilled. */
    public void discardJerricanSeals(Connection c, UUID id) throws SQLException {
        rememberSeals(c, id);
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE containers SET top_seal = NULL, bottom_seal = NULL WHERE id = ? AND container_type LIKE 'JERRICAN%' AND (top_seal IS NOT NULL OR bottom_seal IS NOT NULL)")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    /** The container that once carried this seal number, before the seal was discarded, if any. */
    public Optional<String> sealDiscardedFrom(Connection c, String seal) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT k.barcode FROM discarded_seals d JOIN containers k ON k.id = d.container_id WHERE d.seal = ?")) {
            ps.setString(1, seal);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(); }
        }
    }

    public void setLocation(Connection c, UUID id, UUID siteId, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE containers SET site_id=?, status=?, updated_at=now() WHERE id=?")) {
            ps.setObject(1, siteId); ps.setString(2, status); ps.setObject(3, id);
            ps.executeUpdate();
        }
        publish(c, id);
    }

    /** Rum currently held in opened jerricans at the site. */
    public double openedVolume(Connection c, UUID siteId) throws SQLException {
        return sum(c, "SELECT COALESCE(SUM(current_volume_ml),0) FROM containers WHERE site_id=? AND status='OPENED' " +
                "AND container_type LIKE 'JERRICAN%'", siteId, null);
    }

    /** Rum originally in jerricans opened since the given time. */
    public double volumeOpenedSince(Connection c, UUID siteId, java.time.LocalDateTime since) throws SQLException {
        return sum(c, "SELECT COALESCE(SUM(volume_g),0) FROM container_events WHERE site_id=? AND event_type='OPEN' AND at >= ?", siteId, since);
    }

    /** Rum in jerricans that were closed back (sealed again) since the given time: they are no longer among the opened jerricans. */
    public double volumeClosedBackSince(Connection c, UUID siteId, java.time.LocalDateTime since) throws SQLException {
        return sum(c, "SELECT COALESCE(SUM(volume_g),0) FROM container_events WHERE site_id=? AND event_type='CLOSE_BACK' AND at >= ?", siteId, since);
    }

    private double sum(Connection c, String sql, UUID siteId, java.time.LocalDateTime since) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, siteId);
            if (since != null) ps.setTimestamp(2, Timestamp.valueOf(since));
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getDouble(1); }
        }
    }

    private void publish(Connection c, UUID id) throws SQLException {
        Optional<Container> k = findById(c, id, false);
        if (k.isPresent()) Outbox.upsert(c, "CONTAINER", id, k.get());
    }
}
