package org.jai.fuelmarking.dao;

import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;

import java.sql.*;
import java.util.*;

/** Master data is read-only locally: it arrives from the central system (Kafka) via upsert methods. */
public class MasterDataDao {

    public List<Site> sites(Connection c) throws SQLException {
        List<Site> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, code, name, site_type FROM marking_sites WHERE active ORDER BY name")) {
            while (rs.next()) out.add(new Site(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)));
        }
        return out;
    }

    public List<Omc> omcs(Connection c) throws SQLException {
        List<Omc> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, code, name FROM oil_marketing_companies WHERE active ORDER BY name")) {
            while (rs.next()) out.add(new Omc(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)));
        }
        return out;
    }

    public List<FuelType> fuelTypes(Connection c) throws SQLException {
        List<FuelType> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT code, name, rum_type_code FROM fuel_types WHERE active ORDER BY name")) {
            while (rs.next()) out.add(new FuelType(rs.getString(1), rs.getString(2), rs.getString(3)));
        }
        return out;
    }

    /** The places a mobile site can work, as head office last told us. */
    public List<Location> locations(Connection c) throws SQLException {
        List<Location> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, code, name FROM locations WHERE active ORDER BY name")) {
            while (rs.next()) out.add(new Location(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)));
        }
        return out;
    }

    public Optional<Location> location(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, code, name FROM locations WHERE active AND id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(new Location(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))) : Optional.empty(); }
        }
    }

    public List<RumType> rumTypes(Connection c) throws SQLException {
        List<RumType> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT code, name, color FROM rum_types WHERE active ORDER BY name")) {
            while (rs.next()) out.add(new RumType(rs.getString(1), rs.getString(2), rs.getString(3)));
        }
        return out;
    }

    /** code -> colour of every rum type, for the screens that show a swatch next to a rum type code. */
    public Map<String, String> rumColors(Connection c) throws SQLException {
        Map<String, String> out = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT code, color FROM rum_types")) {
            while (rs.next()) out.put(rs.getString(1), rs.getString(2));
        }
        return out;
    }

    public Optional<FuelType> fuelType(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT code, name, rum_type_code FROM fuel_types WHERE active AND code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(new FuelType(rs.getString(1), rs.getString(2), rs.getString(3))) : Optional.empty(); }
        }
    }

    public Optional<RumType> rumType(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT code, name, color FROM rum_types WHERE active AND code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(new RumType(rs.getString(1), rs.getString(2), rs.getString(3))) : Optional.empty(); }
        }
    }

    /** Active container types of one category (BULK or JERRICAN), for the drop-downs. */
    public List<ContainerType> containerTypes(Connection c, String category) throws SQLException {
        List<ContainerType> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT code, name, category, seals, default_capacity_g FROM container_types WHERE active AND category = ? ORDER BY name")) {
            ps.setString(1, category);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(type(rs)); }
        }
        return out;
    }

    /** An active container type by code. */
    public Optional<ContainerType> containerType(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT code, name, category, seals, default_capacity_g FROM container_types WHERE active AND code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(type(rs)) : Optional.empty(); }
        }
    }

    private static ContainerType type(ResultSet rs) throws SQLException {
        return new ContainerType(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getObject(5) == null ? null : rs.getDouble(5));
    }

    /** Head office's record of a jerrican, if it has registered one with this barcode (and not retired it). */
    public Optional<RegisteredJerrican> registeredJerrican(Connection c, String barcode) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT j.id, j.barcode, j.capacity_g, j.rum_type_code, t.code, t.name, t.category, t.seals, t.default_capacity_g
                FROM registered_jerricans j JOIN container_types t ON t.code = j.container_type_code
                WHERE j.active AND j.barcode = ?""")) {
            ps.setString(1, barcode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new RegisteredJerrican(rs.getObject(1, UUID.class), rs.getString(2),
                        new ContainerType(rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getObject(9) == null ? null : rs.getDouble(9)),
                        rs.getDouble(3), rs.getString(4))) : Optional.empty();
            }
        }
    }

    public List<AppUser> users(Connection c, UUID siteId) throws SQLException {
        List<AppUser> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, username, full_name, role, site_id FROM app_users WHERE active AND (site_id = ? OR site_id IS NULL) ORDER BY full_name")) {
            ps.setObject(1, siteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new AppUser(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        Role.valueOf(rs.getString(4)), rs.getObject(5, UUID.class)));
            }
        }
        return out;
    }

    /** Sets the rum type a fuel type is marked with (head office does this; the desktop receives it with the fuel type). */
    public void setFuelRum(Connection c, String fuelCode, String rumTypeCode) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE fuel_types SET rum_type_code = ?, updated_at = now() WHERE code = ?")) {
            ps.setString(1, rumTypeCode); ps.setString(2, fuelCode);
            ps.executeUpdate();
        }
    }

    public void upsertSite(Connection c, Site s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO marking_sites(id, code, name, site_type) VALUES (?,?,?,?) " +
                "ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, site_type=EXCLUDED.site_type, updated_at=now()")) {
            ps.setObject(1, s.id()); ps.setString(2, s.code()); ps.setString(3, s.name()); ps.setString(4, s.siteType());
            ps.executeUpdate();
        }
    }

    public void upsertOmc(Connection c, Omc o) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO oil_marketing_companies(id, code, name) VALUES (?,?,?) " +
                "ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, updated_at=now()")) {
            ps.setObject(1, o.id()); ps.setString(2, o.code()); ps.setString(3, o.name());
            ps.executeUpdate();
        }
    }
}
