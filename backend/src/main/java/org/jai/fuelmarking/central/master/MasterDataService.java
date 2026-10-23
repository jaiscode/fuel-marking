package org.jai.fuelmarking.central.master;

import org.jai.fuelmarking.central.auth.AuditService;
import org.jai.fuelmarking.central.security.PinHasher;
import org.jai.fuelmarking.central.security.PinPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MasterDataService {
    public record Site(UUID id, String code, String name, String siteType, boolean active) {}
    public record Omc(UUID id, String code, String name, boolean active) {}
    /** Every fuel type is marked with one rum type; rumTypeCode is looked up (it is what the sites are told). */
    public record FuelType(UUID id, String code, String name, UUID rumTypeId, String rumTypeCode, boolean active) {}
    public record Location(UUID id, String code, String name, boolean active) {}
    public record RumType(UUID id, String code, String name, String color, boolean active) {}
    /** A jerrican head office has registered; capacityG is grams of rum. */
    public record ContainerType(UUID id, String code, String name, String category, int seals, Double defaultCapacityG, boolean active) {}
    public record Jerrican(UUID id, String barcode, UUID containerTypeId, double capacityG, UUID rumTypeId, boolean active) {}
    /** A registered jerrican with where it is now, as far as the sites have reported. */
    public record JerricanRow(UUID id, String barcode, UUID containerTypeId, String containerTypeCode, String containerTypeName, int seals,
                              double capacityG, UUID rumTypeId, String rumTypeCode,
                              String rumColor, boolean active, String status, String siteName, Double volumeG) {}
    public record User(UUID id, String username, String fullName, String role, UUID siteId, boolean active) {}

    private static final Set<String> SITE_TYPES = Set.of("FIXED", "MOBILE", "WAREHOUSE");
    private static final Set<String> CATEGORIES = Set.of("BULK", "JERRICAN");
    private static final Set<String> ROLES = Set.of("WAREHOUSE_MANAGER", "SUPERVISOR", "DOSING_OPERATOR", "DISPENSE_OPERATOR");

    private final JdbcTemplate jdbc;
    private final MasterDataOutbox outbox;
    private final AuditService audit;

    public MasterDataService(JdbcTemplate jdbc, MasterDataOutbox outbox, AuditService audit) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
    }

    private static final RowMapper<Site> SITE = (rs, n) -> new Site(rs.getObject("id", UUID.class), rs.getString("code"),
            rs.getString("name"), rs.getString("site_type"), rs.getBoolean("active"));
    private static final RowMapper<Omc> OMC = (rs, n) -> new Omc(rs.getObject("id", UUID.class), rs.getString("code"),
            rs.getString("name"), rs.getBoolean("active"));
    private static final RowMapper<User> USER = (rs, n) -> new User(rs.getObject("id", UUID.class), rs.getString("username"),
            rs.getString("full_name"), rs.getString("role"), rs.getObject("site_id", UUID.class), rs.getBoolean("active"));

    private static final RowMapper<FuelType> FUEL = (rs, n) -> new FuelType(rs.getObject("id", UUID.class), rs.getString("code"),
            rs.getString("name"), rs.getObject("rum_type_id", UUID.class), rs.getString("rum_type_code"), rs.getBoolean("active"));
    private static final RowMapper<RumType> RUM = (rs, n) -> new RumType(rs.getObject("id", UUID.class), rs.getString("code"),
            rs.getString("name"), rs.getString("color"), rs.getBoolean("active"));
    private static final RowMapper<ContainerType> CONTAINER_TYPE = (rs, n) -> new ContainerType(rs.getObject("id", UUID.class), rs.getString("code"),
            rs.getString("name"), rs.getString("category"), rs.getInt("seals"),
            rs.getObject("default_capacity_g") == null ? null : rs.getDouble("default_capacity_g"), rs.getBoolean("active"));
    private static final RowMapper<Jerrican> JERRICAN = (rs, n) -> new Jerrican(rs.getObject("id", UUID.class), rs.getString("barcode"),
            rs.getObject("container_type_id", UUID.class), rs.getDouble("capacity_g"), rs.getObject("rum_type_id", UUID.class), rs.getBoolean("active"));

    public List<FuelType> fuelTypes() {
        return jdbc.query("SELECT f.*, r.code AS rum_type_code FROM fuel_types f LEFT JOIN rum_types r ON r.id = f.rum_type_id ORDER BY f.name", FUEL);
    }
    public List<ContainerType> containerTypes() { return jdbc.query("SELECT * FROM container_types ORDER BY category, name", CONTAINER_TYPE); }
    public List<Location> locations() {
        return jdbc.query("SELECT * FROM locations ORDER BY name", (rs, n) -> new Location(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getBoolean("active")));
    }

    @Transactional
    public Location saveLocation(Location l) {
        require(l.code(), "code"); require(l.name(), "name");
        Location saved = new Location(l.id() == null ? UUID.randomUUID() : l.id(), l.code().trim().toUpperCase(), l.name().trim(), l.active());
        jdbc.update("""
                INSERT INTO locations(id, code, name, active) VALUES (?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, active=EXCLUDED.active, updated_at=now()""",
                saved.id(), saved.code(), saved.name(), saved.active());
        audit.record(l.id() == null ? "LOCATION_CREATE" : "LOCATION_UPDATE", saved.id().toString(), saved.code() + (saved.active() ? "" : " (inactive)"));
        outbox.enqueue("LOCATION", saved.id(), saved);
        return saved;
    }

    public List<RumType> rumTypes() { return jdbc.query("SELECT * FROM rum_types ORDER BY name", RUM); }
    public List<Jerrican> jerricans() { return jdbc.query("SELECT * FROM registered_jerricans ORDER BY barcode", JERRICAN); }

    /** Registered jerricans with the latest state a site reported for the same barcode (status, place, rum held). */
    public List<JerricanRow> jerricanRows() {
        return jdbc.query("""
                SELECT j.id, j.barcode, j.container_type_id, ct.code AS type_code, ct.name AS type_name, ct.seals, j.capacity_g, j.rum_type_id,
                       r.code AS rum_code, r.color AS rum_color, j.active,
                       e.payload ->> 'status' AS status, s.name AS site_name, (e.payload ->> 'currentVolumeMl')::numeric AS volume
                FROM registered_jerricans j
                JOIN rum_types r ON r.id = j.rum_type_id
                JOIN container_types ct ON ct.id = j.container_type_id
                LEFT JOIN synced_entities e ON e.entity_type = 'CONTAINER' AND e.payload ->> 'barcode' = j.barcode
                LEFT JOIN marking_sites s ON s.id::text = e.payload ->> 'siteId'
                ORDER BY j.barcode""", (rs, n) -> new JerricanRow(rs.getObject("id", UUID.class), rs.getString("barcode"),
                rs.getObject("container_type_id", UUID.class), rs.getString("type_code"), rs.getString("type_name"), rs.getInt("seals"),
                rs.getDouble("capacity_g"), rs.getObject("rum_type_id", UUID.class), rs.getString("rum_code"),
                rs.getString("rum_color"), rs.getBoolean("active"), rs.getString("status"), rs.getString("site_name"),
                rs.getObject("volume") == null ? null : rs.getDouble("volume")));
    }

    @Transactional
    public FuelType saveFuelType(FuelType f) {
        require(f.code(), "code"); require(f.name(), "name");
        if (f.rumTypeId() == null) throw new IllegalArgumentException("Choose the rum type this fuel is marked with (rumTypeId)");
        String rumCode;
        try { rumCode = jdbc.queryForObject("SELECT code FROM rum_types WHERE id = ? AND active", String.class, f.rumTypeId()); }
        catch (org.springframework.dao.EmptyResultDataAccessException e) { throw new IllegalArgumentException("Choose an active rum type for this fuel"); }
        FuelType saved = new FuelType(f.id() == null ? UUID.randomUUID() : f.id(), f.code().trim().toUpperCase(), f.name().trim(), f.rumTypeId(), rumCode, f.active());
        jdbc.update("""
                INSERT INTO fuel_types(id, code, name, rum_type_id, active) VALUES (?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, rum_type_id=EXCLUDED.rum_type_id, active=EXCLUDED.active, updated_at=now()""",
                saved.id(), saved.code(), saved.name(), saved.rumTypeId(), saved.active());
        audit.record(f.id() == null ? "FUEL_TYPE_CREATE" : "FUEL_TYPE_UPDATE", saved.id().toString(), saved.code() + " -> " + rumCode + (saved.active() ? "" : " (inactive)"));
        outbox.enqueue("FUEL_TYPE", saved.id(), saved);
        return saved;
    }

    private static final java.util.regex.Pattern COLOUR = java.util.regex.Pattern.compile("^#[0-9A-Fa-f]{6}$");

    @Transactional
    public RumType saveRumType(RumType r) {
        require(r.code(), "code"); require(r.name(), "name");
        String color = r.color() == null || r.color().isBlank() ? "#808080" : r.color().trim();
        if (!COLOUR.matcher(color).matches()) throw new IllegalArgumentException("color must look like #RRGGBB");
        RumType saved = new RumType(r.id() == null ? UUID.randomUUID() : r.id(), r.code().trim().toUpperCase(), r.name().trim(), color.toUpperCase(), r.active());
        jdbc.update("""
                INSERT INTO rum_types(id, code, name, color, active) VALUES (?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, color=EXCLUDED.color, active=EXCLUDED.active, updated_at=now()""",
                saved.id(), saved.code(), saved.name(), saved.color(), saved.active());
        audit.record(r.id() == null ? "RUM_TYPE_CREATE" : "RUM_TYPE_UPDATE", saved.id().toString(), saved.code() + " " + saved.color() + (saved.active() ? "" : " (inactive)"));
        outbox.enqueue("RUM_TYPE", saved.id(), saved);
        return saved;
    }

    @Transactional
    public ContainerType saveContainerType(ContainerType t) {
        require(t.code(), "code"); require(t.name(), "name");
        if (!CATEGORIES.contains(t.category())) throw new IllegalArgumentException("category must be one of " + CATEGORIES);
        if (t.seals() < 0 || t.seals() > 2) throw new IllegalArgumentException("seals must be 0, 1 or 2");
        if ("JERRICAN".equals(t.category()) && t.seals() < 1) throw new IllegalArgumentException("a jerrican carries at least one seal");
        if (t.defaultCapacityG() != null && t.defaultCapacityG() <= 0) throw new IllegalArgumentException("default capacity must be more than zero");
        ContainerType saved = new ContainerType(t.id() == null ? UUID.randomUUID() : t.id(), t.code().trim().toUpperCase(), t.name().trim(),
                t.category(), t.seals(), t.defaultCapacityG(), t.active());
        if (t.id() != null) {
            // containers and registrations already use the type: changing what kind of container it is would change what they are
            Map<String, Object> old = jdbc.queryForList("SELECT code, category, seals FROM container_types WHERE id = ?", saved.id()).stream().findFirst().orElse(null);
            if (old != null && (!old.get("category").equals(saved.category()) || !old.get("code").equals(saved.code())))
                throw new IllegalArgumentException("The code and category of a container type cannot be changed - add a new type instead");
        }
        jdbc.update("""
                INSERT INTO container_types(id, code, name, category, seals, default_capacity_g, active) VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, category=EXCLUDED.category, seals=EXCLUDED.seals,
                    default_capacity_g=EXCLUDED.default_capacity_g, active=EXCLUDED.active, updated_at=now()""",
                saved.id(), saved.code(), saved.name(), saved.category(), saved.seals(), saved.defaultCapacityG(), saved.active());
        audit.record(t.id() == null ? "CONTAINER_TYPE_CREATE" : "CONTAINER_TYPE_UPDATE", saved.id().toString(),
                saved.code() + " " + saved.category() + " seals=" + saved.seals() + (saved.active() ? "" : " (inactive)"));
        outbox.enqueue("CONTAINER_TYPE", saved.id(), saved);
        return saved;
    }

    @Transactional
    public Jerrican saveJerrican(Jerrican j) {
        require(j.barcode(), "barcode");
        if (j.containerTypeId() == null) throw new IllegalArgumentException("containerTypeId is required");
        ContainerType type = containerTypes().stream().filter(t -> t.id().equals(j.containerTypeId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown container type"));
        if (!"JERRICAN".equals(type.category())) throw new IllegalArgumentException(type.name() + " is not a jerrican type");
        if (j.capacityG() <= 0) throw new IllegalArgumentException("capacity must be more than zero");
        if (j.rumTypeId() == null) throw new IllegalArgumentException("rumTypeId is required");
        String rumCode;
        try { rumCode = jdbc.queryForObject("SELECT code FROM rum_types WHERE id = ?", String.class, j.rumTypeId()); }
        catch (org.springframework.dao.EmptyResultDataAccessException e) { throw new IllegalArgumentException("Unknown rum type"); }
        Jerrican saved = new Jerrican(j.id() == null ? UUID.randomUUID() : j.id(), j.barcode().trim(), j.containerTypeId(), j.capacityG(), j.rumTypeId(), j.active());
        jdbc.update("""
                INSERT INTO registered_jerricans(id, barcode, container_type_id, capacity_g, rum_type_id, active) VALUES (?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET barcode=EXCLUDED.barcode, container_type_id=EXCLUDED.container_type_id, capacity_g=EXCLUDED.capacity_g,
                    rum_type_id=EXCLUDED.rum_type_id, active=EXCLUDED.active, updated_at=now()""",
                saved.id(), saved.barcode(), saved.containerTypeId(), saved.capacityG(), saved.rumTypeId(), saved.active());
        audit.record(j.id() == null ? "JERRICAN_REGISTER" : "JERRICAN_UPDATE", saved.id().toString(),
                saved.barcode() + " " + type.code() + " " + rumCode + (saved.active() ? "" : " (inactive)"));
        outbox.enqueue("JERRICAN", saved.id(), jerricanMessage(saved, type.code(), rumCode));
        return saved;
    }

    @Transactional
    public int saveJerricans(AdminController.BulkJerricans b) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        int n = 0;
        for (String raw : b.barcodes()) {
            String code = raw == null ? "" : raw.trim();
            if (code.isEmpty()) continue;
            if (!seen.add(code)) throw new IllegalArgumentException("Barcode " + code + " is listed twice");
            saveJerrican(new Jerrican(null, code, b.containerTypeId(), b.capacityG(), b.rumTypeId(), true));
            n++;
        }
        if (n == 0) throw new IllegalArgumentException("barcodes is required");
        return n;
    }

    /** Sites identify the rum type and container type by their codes, so the message carries the codes (and the ids for reference). */
    private static Map<String, Object> jerricanMessage(Jerrican j, String typeCode, String rumCode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.id()); m.put("barcode", j.barcode()); m.put("containerTypeId", j.containerTypeId()); m.put("containerTypeCode", typeCode);
        m.put("capacityG", j.capacityG()); m.put("rumTypeId", j.rumTypeId()); m.put("rumTypeCode", rumCode); m.put("active", j.active());
        return m;
    }

    public List<Site> sites() { return jdbc.query("SELECT * FROM marking_sites ORDER BY name", SITE); }
    public List<Omc> omcs() { return jdbc.query("SELECT * FROM oil_marketing_companies ORDER BY name", OMC); }
    public List<User> users() { return jdbc.query("SELECT * FROM app_users ORDER BY full_name", USER); }

    @Transactional
    public Site saveSite(Site s) {
        require(s.code(), "code"); require(s.name(), "name");
        if (!SITE_TYPES.contains(s.siteType())) throw new IllegalArgumentException("siteType must be one of " + SITE_TYPES);
        Site saved = s.id() == null ? new Site(UUID.randomUUID(), s.code(), s.name(), s.siteType(), s.active()) : s;
        jdbc.update("""
                INSERT INTO marking_sites(id, code, name, site_type, active) VALUES (?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, site_type=EXCLUDED.site_type,
                    active=EXCLUDED.active, updated_at=now()""", saved.id(), saved.code(), saved.name(), saved.siteType(), saved.active());
        audit.record(s.id() == null ? "SITE_CREATE" : "SITE_UPDATE", saved.id().toString(), saved.code() + (saved.active() ? "" : " (inactive)"));
        outbox.enqueue("SITE", saved.id(), saved);
        return saved;
    }

    @Transactional
    public Omc saveOmc(Omc o) {
        require(o.code(), "code"); require(o.name(), "name");
        Omc saved = o.id() == null ? new Omc(UUID.randomUUID(), o.code(), o.name(), o.active()) : o;
        jdbc.update("""
                INSERT INTO oil_marketing_companies(id, code, name, active) VALUES (?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, active=EXCLUDED.active, updated_at=now()""",
                saved.id(), saved.code(), saved.name(), saved.active());
        audit.record(o.id() == null ? "OMC_CREATE" : "OMC_UPDATE", saved.id().toString(), saved.code() + (saved.active() ? "" : " (inactive)"));
        outbox.enqueue("OMC", saved.id(), saved);
        return saved;
    }

    @Transactional
    public User saveUser(User u) {
        require(u.username(), "username"); require(u.fullName(), "fullName");
        if (!ROLES.contains(u.role())) throw new IllegalArgumentException("role must be one of " + ROLES);
        if (u.siteId() != null && jdbc.queryForObject("SELECT count(*) FROM marking_sites WHERE id = ?", Integer.class, u.siteId()) == 0)
            throw new IllegalArgumentException("Unknown site " + u.siteId());
        User saved = u.id() == null ? new User(UUID.randomUUID(), u.username(), u.fullName(), u.role(), u.siteId(), u.active()) : u;
        jdbc.update("""
                INSERT INTO app_users(id, username, full_name, role, site_id, active) VALUES (?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET username=EXCLUDED.username, full_name=EXCLUDED.full_name, role=EXCLUDED.role,
                    site_id=EXCLUDED.site_id, active=EXCLUDED.active, updated_at=now()""",
                saved.id(), saved.username(), saved.fullName(), saved.role(), saved.siteId(), saved.active());
        audit.record(u.id() == null ? "USER_CREATE" : "USER_UPDATE", saved.id().toString(), saved.username() + " " + saved.role() + (saved.active() ? "" : " (inactive)"));
        enqueueUser(saved.id());
        return saved;
    }

    /** Queues everything again, e.g. after a Kafka topic was lost. Sites first, then users. */
    @Transactional
    public int republishAll() {
        audit.record("MASTER_DATA_REPUBLISH", null, null);
        sites().forEach(s -> outbox.enqueue("SITE", s.id(), s));
        omcs().forEach(o -> outbox.enqueue("OMC", o.id(), o));
        fuelTypes().forEach(f -> outbox.enqueue("FUEL_TYPE", f.id(), f));
        containerTypes().forEach(t -> outbox.enqueue("CONTAINER_TYPE", t.id(), t));   // container types and rum types before the jerricans,
        rumTypes().forEach(r -> outbox.enqueue("RUM_TYPE", r.id(), r));              // which refer to both
        jerricans().forEach(j -> outbox.enqueue("JERRICAN", j.id(), jerricanMessage(j,
                jdbc.queryForObject("SELECT code FROM container_types WHERE id = ?", String.class, j.containerTypeId()),
                jdbc.queryForObject("SELECT code FROM rum_types WHERE id = ?", String.class, j.rumTypeId()))));
        locations().forEach(l -> outbox.enqueue("LOCATION", l.id(), l));
        users().forEach(u -> enqueueUser(u.id()));
        return locations().size() + sites().size() + omcs().size() + fuelTypes().size() + containerTypes().size() + rumTypes().size() + jerricans().size() + users().size();
    }

    /** Sets or resets a user's PIN. The hash (never the PIN) is stored and replicated to the sites. */
    @Transactional
    public void setPin(UUID userId, String pin) {
        PinPolicy.check(pin);
        if (jdbc.update("UPDATE app_users SET pin_hash = ?, updated_at = now() WHERE id = ?", PinHasher.hash(pin), userId) == 0)
            throw new IllegalArgumentException("Unknown user");
        audit.record("PIN_SET", userId.toString(), null); // the PIN itself is never recorded
        enqueueUser(userId);
    }

    /** The replicated user record carries the PIN hash so desktops can authenticate offline. */
    private void enqueueUser(UUID id) {
        jdbc.query("SELECT * FROM app_users WHERE id = ?", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getObject("id", UUID.class));
            m.put("username", rs.getString("username"));
            m.put("fullName", rs.getString("full_name"));
            m.put("role", rs.getString("role"));
            m.put("siteId", rs.getObject("site_id", UUID.class));
            m.put("active", rs.getBoolean("active"));
            m.put("pinHash", rs.getString("pin_hash"));
            outbox.enqueue("USER", id, m);
        }, id);
    }

    private static void require(String v, String field) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
