package org.jai.fuelmarking.central.master;

import org.jai.fuelmarking.central.auth.AuditService;
import org.jai.fuelmarking.central.master.MasterDataService.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Master data management for the web app. Records are deactivated (active=false), never deleted. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final MasterDataService service;
    private final ApiKeys apiKeys;
    private final AuditService audit;
    private final MasterDataOutbox outbox;

    public AdminController(MasterDataService service, ApiKeys apiKeys, AuditService audit, MasterDataOutbox outbox) {
        this.outbox = outbox;
        this.service = service;
        this.apiKeys = apiKeys;
        this.audit = audit;
    }

    @GetMapping("/sites") List<Site> sites() { return service.sites(); }
    @PostMapping("/sites") ResponseEntity<Site> createSite(@RequestBody Site s) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveSite(new Site(null, s.code(), s.name(), s.siteType() == null ? "FIXED" : s.siteType(), true)));
    }
    @PutMapping("/sites/{id}") Site updateSite(@PathVariable UUID id, @RequestBody Site s) {
        return service.saveSite(new Site(id, s.code(), s.name(), s.siteType(), s.active()));
    }

    /** Issues a new API key for the site's desktop installation. The key is shown once. */
    @PostMapping("/sites/{id}/api-key") Map<String, String> issueKey(@PathVariable UUID id) {
        if (service.sites().stream().noneMatch(s -> s.id().equals(id))) throw new IllegalArgumentException("Unknown site");
        String key = apiKeys.issue(id);
        audit.record("API_KEY_ISSUED", id.toString(), null); // the key itself is never recorded
        return Map.of("siteId", id.toString(), "apiKey", key);
    }

    @GetMapping("/omcs") List<Omc> omcs() { return service.omcs(); }
    @PostMapping("/omcs") ResponseEntity<Omc> createOmc(@RequestBody Omc o) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveOmc(new Omc(null, o.code(), o.name(), true)));
    }
    @PutMapping("/omcs/{id}") Omc updateOmc(@PathVariable UUID id, @RequestBody Omc o) {
        return service.saveOmc(new Omc(id, o.code(), o.name(), o.active()));
    }

    @GetMapping("/fuel-types") List<FuelType> fuelTypes() { return service.fuelTypes(); }
    @PostMapping("/fuel-types") ResponseEntity<FuelType> createFuelType(@RequestBody FuelType f) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveFuelType(new FuelType(null, f.code(), f.name(), f.rumTypeId(), null, true)));
    }
    @PutMapping("/fuel-types/{id}") FuelType updateFuelType(@PathVariable UUID id, @RequestBody FuelType f) {
        return service.saveFuelType(new FuelType(id, f.code(), f.name(), f.rumTypeId(), null, f.active()));
    }

    @GetMapping("/container-types") List<ContainerType> containerTypes() { return service.containerTypes(); }
    @PostMapping("/container-types") ResponseEntity<ContainerType> createContainerType(@RequestBody ContainerType t) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveContainerType(new ContainerType(null, t.code(), t.name(), t.category(), t.seals(), t.defaultCapacityG(), true)));
    }
    @PutMapping("/container-types/{id}") ContainerType updateContainerType(@PathVariable UUID id, @RequestBody ContainerType t) {
        return service.saveContainerType(new ContainerType(id, t.code(), t.name(), t.category(), t.seals(), t.defaultCapacityG(), t.active()));
    }

    @GetMapping("/locations") List<Location> locations() { return service.locations(); }
    @PostMapping("/locations") ResponseEntity<Location> createLocation(@RequestBody Location l) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveLocation(new Location(null, l.code(), l.name(), true)));
    }
    @PutMapping("/locations/{id}") Location updateLocation(@PathVariable UUID id, @RequestBody Location l) {
        return service.saveLocation(new Location(id, l.code(), l.name(), l.active()));
    }

    @GetMapping("/rum-types") List<RumType> rumTypes() { return service.rumTypes(); }
    @PostMapping("/rum-types") ResponseEntity<RumType> createRumType(@RequestBody RumType r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveRumType(new RumType(null, r.code(), r.name(), r.color(), true)));
    }
    @PutMapping("/rum-types/{id}") RumType updateRumType(@PathVariable UUID id, @RequestBody RumType r) {
        return service.saveRumType(new RumType(id, r.code(), r.name(), r.color(), r.active()));
    }

    /** Registered jerricans with their current state as reported by the sites. */
    @GetMapping("/jerricans") List<JerricanRow> jerricans() { return service.jerricanRows(); }
    @PostMapping("/jerricans") ResponseEntity<Jerrican> registerJerrican(@RequestBody Jerrican j) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveJerrican(new Jerrican(null, j.barcode(), j.containerTypeId(), j.capacityG(), j.rumTypeId(), true)));
    }
    @PutMapping("/jerricans/{id}") Jerrican updateJerrican(@PathVariable UUID id, @RequestBody Jerrican j) {
        return service.saveJerrican(new Jerrican(id, j.barcode(), j.containerTypeId(), j.capacityG(), j.rumTypeId(), j.active()));
    }
    /** Registers many jerricans of the same kind at once, e.g. a delivery. All or none: one bad id rejects the lot. */
    public record BulkJerricans(List<String> barcodes, UUID containerTypeId, double capacityG, UUID rumTypeId) {}
    @PostMapping("/jerricans/bulk") ResponseEntity<Map<String, Integer>> registerJerricans(@RequestBody BulkJerricans b) {
        if (b.barcodes() == null || b.barcodes().isEmpty()) throw new IllegalArgumentException("barcodes is required");
        int n = service.saveJerricans(b);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("registered", n));
    }

    @GetMapping("/users") List<User> users() { return service.users(); }
    @PostMapping("/users") ResponseEntity<User> createUser(@RequestBody User u) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveUser(new User(null, u.username(), u.fullName(), u.role(), u.siteId(), true)));
    }
    @PutMapping("/users/{id}") User updateUser(@PathVariable UUID id, @RequestBody User u) {
        return service.saveUser(new User(id, u.username(), u.fullName(), u.role(), u.siteId(), u.active()));
    }

    /** Sets or resets a user's PIN. The body is {"pin":"123789"}; the PIN is never stored or returned. */
    @PutMapping("/users/{id}/pin") ResponseEntity<Void> setPin(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        service.setPin(id, body.get("pin"));
        return ResponseEntity.noContent().build();
    }

    /** Newest first. Optional {@code action} filter, e.g. {@code SYNC_CONFLICT} or {@code API_KEY_ISSUED}. */
    @GetMapping("/audit") List<Map<String, Object>> audit(@RequestParam(defaultValue = "100") int limit,
                                                         @RequestParam(required = false) String action) {
        if (action != null && action.length() > 50) throw new IllegalArgumentException("Unknown action");
        return audit.recent(limit, action);
    }

    /** How many master-data changes are still waiting to be delivered to the sites (non-zero for long = Kafka trouble). */
    @GetMapping("/master-data/status") MasterDataOutbox.Status masterDataStatus() { return outbox.status(); }

    @PostMapping("/master-data/republish") Map<String, Integer> republish() {
        return Map.of("published", service.republishAll());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, String>> duplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Code, username or barcode already exists"));
    }
}
