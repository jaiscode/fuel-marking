package org.jai.fuelmarking.dao;

import org.jai.fuelmarking.model.Records.Transfer;
import org.jai.fuelmarking.model.Records.TransferItem;

import java.sql.*;
import java.util.*;

public class TransferDao {

    public Transfer insert(Connection c, UUID from, UUID to, UUID dispatchedBy, String kind) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transfers(id, from_site_id, to_site_id, dispatched_by, kind) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, id); ps.setObject(2, from); ps.setObject(3, to); ps.setObject(4, dispatchedBy); ps.setString(5, kind);
            ps.executeUpdate();
        }
        return find(c, id, false).orElseThrow();
    }

    public void addItem(Connection c, UUID transferId, UUID containerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transfer_items(transfer_id, container_id) VALUES (?,?)")) {
            ps.setObject(1, transferId); ps.setObject(2, containerId);
            ps.executeUpdate();
        }
    }

    public Optional<Transfer> find(Connection c, UUID id, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, from_site_id, to_site_id, status, dispatched_at, kind FROM transfers WHERE id=?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        }
    }

    public List<Transfer> incoming(Connection c, UUID toSiteId) throws SQLException {
        List<Transfer> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, from_site_id, to_site_id, status, dispatched_at, kind FROM transfers " +
                "WHERE to_site_id=? AND status='DISPATCHED' ORDER BY dispatched_at")) {
            ps.setObject(1, toSiteId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
        }
        return out;
    }

    /** Items still in transit (not received yet) plus those already received. */
    public List<TransferItem> items(Connection c, UUID transferId) throws SQLException {
        List<TransferItem> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT k.barcode, t.received_at IS NOT NULL FROM transfer_items t " +
                "JOIN containers k ON k.id = t.container_id WHERE t.transfer_id=? ORDER BY k.barcode")) {
            ps.setObject(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new TransferItem(rs.getString(1), rs.getBoolean(2)));
            }
        }
        return out;
    }

    public void recordReceipt(Connection c, UUID transferId, UUID containerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE transfer_items SET received_at=now() WHERE transfer_id=? AND container_id=? AND received_at IS NULL")) {
            ps.setObject(1, transferId); ps.setObject(2, containerId);
            if (ps.executeUpdate() != 1) throw new IllegalStateException("Container is not an outstanding item of this transfer");
        }
    }

    public boolean allReceived(Connection c, UUID transferId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM transfer_items WHERE transfer_id=? AND received_at IS NULL")) {
            ps.setObject(1, transferId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1) == 0; }
        }
    }

    public void markReceived(Connection c, UUID transferId, UUID receivedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE transfers SET status='RECEIVED', received_by=?, received_at=now() WHERE id=?")) {
            ps.setObject(1, receivedBy); ps.setObject(2, transferId);
            ps.executeUpdate();
        }
    }

    /** Publishes the transfer with its items to the sync outbox. */
    public void publish(Connection c, UUID transferId) throws SQLException {
        Transfer t = find(c, transferId, false).orElseThrow();
        Outbox.upsert(c, "TRANSFER", transferId, Map.of("transfer", t, "items", items(c, transferId)));
    }

    private Transfer map(ResultSet rs) throws SQLException {
        return new Transfer(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getTimestamp(5).toLocalDateTime(), rs.getString(6));
    }
}
