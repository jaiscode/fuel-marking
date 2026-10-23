package org.jai.fuelmarking.dao;

import org.jai.fuelmarking.model.CompartmentDetail;
import org.jai.fuelmarking.model.MarkingStatus;
import org.jai.fuelmarking.model.Records.MarkingDose;
import org.jai.fuelmarking.model.TruckMarking;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

public class TruckMarkingDao {
    private static final String COLS =
            "id, uuid, order_number, oil_marketing_company, truck_number, fuel_type, declared_volume, status, " +
            "registered_time, dosed_time, dispensed_time, completed_time, rum_used, site_id, shift_id, omc_id, rum_type, " +
            "dose_calculated_ml, dose_container_id, dose_measured_g, dose_source, trailer_number, deleted_time";

    private static LocalDateTime ts(Timestamp t) { return t == null ? null : t.toLocalDateTime(); }

    private TruckMarking map(Connection c, ResultSet rs) throws SQLException {
        TruckMarking t = new TruckMarking();
        t.setId(rs.getLong(1)); t.setUuid(rs.getObject(2, UUID.class)); t.setOrderNumber(rs.getString(3));
        t.setOilMarketingCompany(rs.getString(4)); t.setTruckNumber(rs.getString(5));
        t.setFuelType(rs.getString(6)); t.setDeclaredVolume(rs.getDouble(7));
        t.setStatus(MarkingStatus.valueOf(rs.getString(8))); t.setRegisteredTime(ts(rs.getTimestamp(9)));
        t.setDosedTime(ts(rs.getTimestamp(10))); t.setDispensedTime(ts(rs.getTimestamp(11)));
        t.setCompletedTime(ts(rs.getTimestamp(12))); t.setRumUsed(rs.getDouble(13));
        t.setSiteId(rs.getObject(14, UUID.class)); t.setShiftId(rs.getObject(15, UUID.class));
        t.setOmcId(rs.getObject(16, UUID.class)); t.setRumType(rs.getString(17));
        t.setDoseCalculatedMl(rs.getDouble(18)); t.setDoseContainerId(rs.getObject(19, UUID.class));
        double measured = rs.getDouble(20);
        t.setDoseMeasuredG(rs.wasNull() ? null : measured); t.setDoseSource(rs.getString(21));
        t.setTrailerNumber(rs.getString(22));
        t.setDeletedTime(ts(rs.getTimestamp(23)));
        t.setCompartments(compartments(c, t.getId()));
        t.setDoses(doses(c, t.getId()));
        return t;
    }

    private List<CompartmentDetail> compartments(Connection c, long markingId) throws SQLException {
        List<CompartmentDetail> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT compartment_number, capacity, actual_volume, fuel_type_code, rum_type_code FROM compartment_details WHERE truck_marking_id = ? ORDER BY id")) {
            ps.setLong(1, markingId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new CompartmentDetail(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getString(4), rs.getString(5)));
            }
        }
        return out;
    }

    private List<MarkingDose> doses(Connection c, long markingId) throws SQLException {
        List<MarkingDose> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT compartment_number, rum_type_code, required_g, measured_g, source, container_id, dosed_at FROM marking_doses WHERE truck_marking_id = ? ORDER BY id")) {
            ps.setLong(1, markingId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new MarkingDose(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getString(5),
                        rs.getObject(6, UUID.class), ts(rs.getTimestamp(7))));
            }
        }
        return out;
    }

    /**
     * The order number is the site code and the registration time to the second; two trucks registered in the same second
     * get -2, -3, ... appended so the unique constraint is never hit.
     */
    public String uniqueOrderNumber(Connection c, String base) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM truck_markings WHERE order_number = ? OR order_number LIKE ?")) {
            ps.setString(1, base);
            ps.setString(2, base + "-%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long taken = rs.getLong(1);
                return taken == 0 ? base : base + "-" + (taken + 1);
            }
        }
    }

    public void insert(Connection c, TruckMarking t, String omcName, UUID registeredBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO truck_markings(uuid, order_number, oil_marketing_company, truck_number, fuel_type, declared_volume, " +
                "status, registered_time, site_id, shift_id, omc_id, rum_type, registered_by, trailer_number) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id")) {
            ps.setObject(1, t.getUuid()); ps.setString(2, t.getOrderNumber()); ps.setString(3, omcName);
            ps.setString(4, t.getTruckNumber()); ps.setString(5, t.getFuelType()); ps.setDouble(6, t.getDeclaredVolume());
            ps.setString(7, t.getStatus().name()); ps.setTimestamp(8, Timestamp.valueOf(t.getRegisteredTime()));
            ps.setObject(9, t.getSiteId()); ps.setObject(10, t.getShiftId()); ps.setObject(11, t.getOmcId());
            ps.setString(12, t.getRumType()); ps.setObject(13, registeredBy); ps.setString(14, t.getTrailerNumber());
            try (ResultSet rs = ps.executeQuery()) { rs.next(); t.setId(rs.getLong(1)); }
        }
        if (t.getCompartments() != null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO compartment_details(truck_marking_id, compartment_number, capacity, actual_volume, fuel_type_code, rum_type_code) VALUES (?,?,?,?,?,?)")) {
                for (CompartmentDetail d : t.getCompartments()) {
                    ps.setLong(1, t.getId()); ps.setString(2, d.getCompartmentNumber());
                    ps.setDouble(3, d.getCapacity()); ps.setDouble(4, d.getActualVolume());
                    ps.setString(5, d.getFuelType()); ps.setString(6, d.getRumType());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        Outbox.upsert(c, "TRUCK_MARKING", t.getUuid(), t);
    }

    public Optional<TruckMarking> findByUuid(Connection c, UUID uuid, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLS + " FROM truck_markings WHERE uuid = ?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setObject(1, uuid);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(map(c, rs)) : Optional.empty(); }
        }
    }

    public List<TruckMarking> listByShift(Connection c, UUID shiftId, MarkingStatus status) throws SQLException {
        List<TruckMarking> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLS + " FROM truck_markings WHERE shift_id = ?" + (status != null ? " AND status = ?" : " AND status <> 'DELETED'") +
                " ORDER BY registered_time")) {
            ps.setObject(1, shiftId);
            if (status != null) ps.setString(2, status.name());
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(c, rs)); }
        }
        return out;
    }

    public List<TruckMarking> listBySite(Connection c, UUID siteId, LocalDateTime from, LocalDateTime to) throws SQLException {
        List<TruckMarking> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLS + " FROM truck_markings WHERE site_id = ? AND status <> 'DELETED' AND registered_time BETWEEN ? AND ? ORDER BY registered_time")) {
            ps.setObject(1, siteId); ps.setTimestamp(2, Timestamp.valueOf(from)); ps.setTimestamp(3, Timestamp.valueOf(to));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(c, rs)); }
        }
        return out;
    }

    /**
     * The dose of one compartment. {@code requiredG} is what its litres call for; {@code measuredG} is what was weighed and is what leaves the
     * jerrican. The truck's totals follow, and once every compartment has been dosed it moves on to DOSED.
     */
    public void recordDose(Connection c, TruckMarking t, String compartment, String rumType, UUID containerId, double requiredG, double measuredG, String source, UUID by) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO marking_doses(truck_marking_id, compartment_number, rum_type_code, required_g, measured_g, source, container_id, dosed_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, t.getId()); ps.setString(2, compartment); ps.setString(3, rumType); ps.setDouble(4, requiredG); ps.setDouble(5, measuredG);
            ps.setString(6, source); ps.setObject(7, containerId); ps.setObject(8, by);
            ps.executeUpdate();
        }
        List<MarkingDose> done = doses(c, t.getId());
        boolean complete = t.getRequiredDoses().stream().allMatch(r -> done.stream().anyMatch(d -> d.compartment().equals(r.compartment())));
        double measuredTotal = done.stream().mapToDouble(MarkingDose::measuredG).sum();
        String allSource = done.stream().anyMatch(d -> "MANUAL".equals(d.source())) ? "MANUAL" : "SCALE";
        update(c, t, (complete ? "status='DOSED', dosed_time=now(), " : "") + "dose_calculated_ml=?, dose_container_id=?, dosed_by=?, dose_measured_g=?, dose_source=?",
                ps -> { ps.setDouble(1, t.calculateRequiredRum()); ps.setObject(2, containerId); ps.setObject(3, by); ps.setDouble(4, measuredTotal); ps.setString(5, allSource); });
    }

    /** Deletes the marking: it stays on record as DELETED (who and when) and counts nowhere. */
    public void recordDeletion(Connection c, TruckMarking t, UUID by) throws SQLException {
        update(c, t, "status='DELETED', deleted_time=now(), deleted_by=?", ps -> ps.setObject(1, by));
    }

    public void recordDispense(Connection c, TruckMarking t, UUID by) throws SQLException {
        update(c, t, "status='DISPENSED', dispensed_time=now(), rum_used=COALESCE(dose_measured_g, dose_calculated_ml), dispensed_by=?",
                ps -> ps.setObject(1, by));
    }

    public void recordVerification(Connection c, TruckMarking t, UUID by) throws SQLException {
        update(c, t, "status='COMPLETED', completed_time=now(), verified_time=now(), verified_by=?",
                ps -> ps.setObject(1, by));
    }

    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private void update(Connection c, TruckMarking t, String set, Binder binder) throws SQLException {
        int n = (int) set.chars().filter(ch -> ch == '?').count();
        try (PreparedStatement ps = c.prepareStatement("UPDATE truck_markings SET " + set + ", updated_at=now() WHERE uuid=?")) {
            binder.bind(ps);
            ps.setObject(n + 1, t.getUuid());
            ps.executeUpdate();
        }
        Outbox.upsert(c, "TRUCK_MARKING", t.getUuid(), findByUuid(c, t.getUuid(), false).orElseThrow());
    }
}
