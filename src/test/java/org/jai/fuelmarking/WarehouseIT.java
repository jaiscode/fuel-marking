package org.jai.fuelmarking;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.*;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.service.*;
import org.jai.fuelmarking.sync.MasterDataApplier;
import org.jai.fuelmarking.sync.MissingDependencyException;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Registered jerricans, empty receipt, seals at refill, rum types and the master lists - against a real Postgres. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WarehouseIT {
    static final byte[] SIG = {1, 2, 3};
    static final String N = Long.toString(System.nanoTime() % 1_000_000, 36);
    static final Site site = new Site(UUID.randomUUID(), "W" + N, "Warehouse " + N, "WAREHOUSE");
    static final Omc omc = new Omc(UUID.randomUUID(), "WO" + N, "Warehouse Oil " + N);
    static final ContainerService containers = new ContainerService();
    static AppUser wh, sup, dosing;

    static AppUser user(java.sql.Connection c, String name, Role r) throws java.sql.SQLException {
        AppUser u = new AppUser(UUID.randomUUID(), name + N, name, r, site.id());
        try (var ps = c.prepareStatement("INSERT INTO app_users(id, username, full_name, role, site_id) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, u.id()); ps.setString(2, u.username()); ps.setString(3, name); ps.setString(4, r.name()); ps.setObject(5, site.id());
            ps.executeUpdate();
        }
        return u;
    }

    static String message(String type, String payloadJson) { return "{\"type\":\"" + type + "\",\"op\":\"UPSERT\",\"payload\":" + payloadJson + "}"; }

    static void apply(String json) { Tx.run(c -> { new MasterDataApplier().apply(c, json); return null; }); }

    @BeforeAll
    static void seed() {
        Tx.run(c -> {
            new MasterDataDao().upsertSite(c, site); new MasterDataDao().upsertOmc(c, omc);
            wh = user(c, "wh", Role.WAREHOUSE_MANAGER); sup = user(c, "sup", Role.SUPERVISOR); dosing = user(c, "dose", Role.DOSING_OPERATOR);
            return null;
        });
        // a second rum type, delivered the way head office delivers it
        apply(message("RUM_TYPE", "{\"id\":\"" + UUID.nameUUIDFromBytes(("rum-b" + N).getBytes()) + "\",\"code\":\"RB" + N + "\",\"name\":\"Rum B\",\"active\":true}"));
    }

    static String rumB() { return "RB" + N; }

    @Test @Order(1)
    void theMasterListsArriveFromHeadOfficeAndStartWithTheUsualFuels() {
        List<FuelType> fuels = Tx.call(c -> new MasterDataDao().fuelTypes(c));
        assertTrue(fuels.stream().map(FuelType::code).toList().containsAll(List.of("PETROL", "DIESEL", "KEROSENE")), fuels.toString());
        assertTrue(Tx.call(c -> new MasterDataDao().rumTypes(c)).stream().anyMatch(r -> r.code().equals("STANDARD")));

        // a rum type's colour comes with it (and a malformed one falls back to grey rather than blocking the message)
        UUID rumId = UUID.nameUUIDFromBytes(("rum-c" + N).getBytes());
        apply(message("RUM_TYPE", "{\"id\":\"" + rumId + "\",\"code\":\"RC" + N + "\",\"name\":\"Coloured\",\"color\":\"#8b4513\",\"active\":true}"));
        assertEquals("#8B4513", Tx.call(c -> new MasterDataDao().rumType(c, "RC" + N)).orElseThrow().color());
        assertEquals("#8B4513", Tx.call(c -> new MasterDataDao().rumColors(c)).get("RC" + N));
        apply(message("RUM_TYPE", "{\"id\":\"" + rumId + "\",\"code\":\"RC" + N + "\",\"name\":\"Coloured\",\"color\":\"brown\",\"active\":true}"));
        assertEquals("#808080", Tx.call(c -> new MasterDataDao().rumType(c, "RC" + N)).orElseThrow().color());

        UUID id = UUID.nameUUIDFromBytes(("fuel" + N).getBytes());
        apply(message("FUEL_TYPE", "{\"id\":\"" + id + "\",\"code\":\"JET" + N + "\",\"name\":\"Jet fuel\",\"rumTypeCode\":\"RC" + N + "\",\"active\":true}"));
        assertTrue(Tx.call(c -> new MasterDataDao().fuelType(c, "JET" + N)).isPresent());
        assertEquals("RC" + N, Tx.call(c -> new MasterDataDao().fuelType(c, "JET" + N)).orElseThrow().rumTypeCode(), "a fuel type comes with the rum type it is marked with");
        apply(message("FUEL_TYPE", "{\"id\":\"" + id + "\",\"code\":\"JET" + N + "\",\"name\":\"Jet fuel\",\"active\":true}"));
        assertEquals("RC" + N, Tx.call(c -> new MasterDataDao().fuelType(c, "JET" + N)).orElseThrow().rumTypeCode(), "a message without a rum type leaves it as it was");
        apply(message("FUEL_TYPE", "{\"id\":\"" + id + "\",\"code\":\"JET" + N + "\",\"name\":\"Jet fuel\",\"active\":false}"));
        assertTrue(Tx.call(c -> new MasterDataDao().fuelType(c, "JET" + N)).isEmpty(), "a retired fuel type can no longer be chosen");
    }

    @Test @Order(1)
    void containerTypesComeFromHeadOfficeWithTheirSealsAndCapacity() {
        var bulk = Tx.call(c -> new MasterDataDao().containerTypes(c, "BULK"));
        var jerricans = Tx.call(c -> new MasterDataDao().containerTypes(c, "JERRICAN"));
        assertTrue(bulk.stream().map(ContainerType::code).toList().containsAll(List.of("IBC", "DRUM")), bulk.toString());
        assertTrue(jerricans.stream().map(ContainerType::code).toList().containsAll(List.of("JERRICAN_PLASTIC", "JERRICAN_METAL")), jerricans.toString());
        assertEquals(1, jerricans.stream().filter(t -> t.code().equals("JERRICAN_PLASTIC")).findFirst().orElseThrow().seals());
        assertEquals(2, jerricans.stream().filter(t -> t.code().equals("JERRICAN_METAL")).findFirst().orElseThrow().seals());
        assertEquals(22_000, jerricans.stream().filter(t -> t.code().equals("JERRICAN_PLASTIC")).findFirst().orElseThrow().defaultCapacityG(), 0.0);
        assertNull(bulk.stream().filter(t -> t.code().equals("IBC")).findFirst().orElseThrow().defaultCapacityG(), "IBC sizes vary");

        // a new jerrican type with a single seal, delivered the way head office delivers it, works at once
        UUID id = UUID.nameUUIDFromBytes(("ct" + N).getBytes());
        String code = "TIN" + N.toUpperCase();
        apply(message("CONTAINER_TYPE", "{\"id\":\"" + id + "\",\"code\":\"" + code + "\",\"name\":\"Tin can\",\"category\":\"JERRICAN\",\"seals\":1,\"defaultCapacityG\":10000,\"active\":true}"));
        Fixtures.register("TIN-" + N, code, 10_000, "STANDARD");
        Container k = containers.receiveEmptyJerrican(wh, site, "TIN-" + N, "STANDARD");
        assertEquals(code, k.type().code());
        assertEquals("Tin can", k.type().name());
        assertEquals(1, Seals.required(k.type()));
        Fixtures.ibc(containers, wh, site, "TINSRC-" + N, 100_000);
        containers.refill(wh, "TIN-" + N, "TINSRC-" + N, 9_000, "TINS-" + N, null);
        assertEquals("SEALED", containers.find("TIN-" + N).orElseThrow().status());

        // a retired type can no longer be chosen for a new bulk container, but containers that already have it still load
        apply(message("CONTAINER_TYPE", "{\"id\":\"" + id + "\",\"code\":\"" + code + "\",\"name\":\"Tin can\",\"category\":\"JERRICAN\",\"seals\":1,\"active\":false}"));
        assertTrue(Tx.call(c -> new MasterDataDao().containerType(c, code)).isEmpty());
        assertEquals(code, containers.find("TIN-" + N).orElseThrow().type().code());
        // a jerrican type that this site has not heard of yet makes the registration wait
        assertThrows(MissingDependencyException.class, () -> apply(message("JERRICAN",
                "{\"id\":\"" + UUID.randomUUID() + "\",\"barcode\":\"X-" + N + "\",\"containerTypeCode\":\"UNHEARD\",\"capacityG\":1000,\"rumTypeCode\":\"STANDARD\",\"active\":true}")));
        // a bulk type cannot be used for a jerrican registration
        assertThrows(MissingDependencyException.class, () -> apply(message("JERRICAN",
                "{\"id\":\"" + UUID.randomUUID() + "\",\"barcode\":\"Y-" + N + "\",\"containerTypeCode\":\"IBC\",\"capacityG\":1000,\"rumTypeCode\":\"STANDARD\",\"active\":true}")));
    }

    @Test @Order(2)
    void aJerricanIsRegisteredBeforeItCanBeReceivedAndTheRumTypeMustMatch() {
        String id = "R1-" + N;
        String unknown = assertThrows(IllegalArgumentException.class, () -> containers.receiveEmptyJerrican(wh, site, id, "STANDARD")).getMessage();
        assertTrue(unknown.contains("not registered at head office"), unknown);

        // a jerrican message that refers to a rum type this site has not seen yet waits (it is retried later)
        assertThrows(MissingDependencyException.class, () -> apply(message("JERRICAN",
                "{\"id\":\"" + UUID.randomUUID() + "\",\"barcode\":\"" + id + "\",\"containerTypeCode\":\"JERRICAN_METAL\",\"capacityG\":18000,\"rumTypeCode\":\"NOPE\",\"active\":true}")));
        assertTrue(containers.registeredJerrican(id).isEmpty());

        apply(message("JERRICAN", "{\"id\":\"" + UUID.nameUUIDFromBytes(id.getBytes()) + "\",\"barcode\":\"" + id
                + "\",\"containerTypeCode\":\"JERRICAN_METAL\",\"capacityG\":18000,\"rumTypeId\":\"" + UUID.randomUUID() + "\",\"rumTypeCode\":\"STANDARD\",\"active\":true}"));
        RegisteredJerrican reg = containers.registeredJerrican(id).orElseThrow();
        assertEquals(2, reg.seals());
        assertEquals("JERRICAN_METAL", reg.type().code());
        assertEquals("Metal jerrican", reg.type().name());

        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.receiveEmptyJerrican(wh, site, id, rumB())).getMessage().contains("registered for rum type STANDARD"));
        assertThrows(IllegalArgumentException.class, () -> containers.receiveEmptyJerrican(wh, site, id, null));
        assertThrows(IllegalStateException.class, () -> containers.receiveEmptyJerrican(sup, site, id, "STANDARD"), "only the warehouse manager receives jerricans");

        Container k = containers.receiveEmptyJerrican(wh, site, id, "STANDARD");
        assertEquals("EMPTY", k.status());
        assertEquals(0, k.currentVolumeMl(), 0.0);
        assertEquals(18_000, k.capacityMl(), 0.0, "capacity and material come from head office, not from what is typed");
        assertEquals("JERRICAN_METAL", k.type().code());
        assertNull(k.topSeal());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.receiveEmptyJerrican(wh, site, id, "STANDARD")).getMessage().contains("already in the system"));

        // a retired registration is no registration
        apply(message("JERRICAN", "{\"id\":\"" + UUID.nameUUIDFromBytes(id.getBytes()) + "\",\"barcode\":\"" + id
                + "\",\"containerTypeCode\":\"JERRICAN_METAL\",\"capacityG\":18000,\"rumTypeCode\":\"STANDARD\",\"active\":false}"));
        assertTrue(containers.registeredJerrican(id).isEmpty());
    }

    @Test @Order(3)
    void sealsAreMandatoryOneForPlasticTwoDifferentOnesForMetal() {
        Fixtures.ibc(containers, wh, site, "SRC-" + N, 900_000);
        Fixtures.register("PL-" + N, "JERRICAN_PLASTIC", 22_000, "STANDARD");
        Fixtures.register("ME-" + N, "JERRICAN_METAL", 18_000, "STANDARD");
        containers.receiveEmptyJerrican(wh, site, "PL-" + N, "STANDARD");
        containers.receiveEmptyJerrican(wh, site, "ME-" + N, "STANDARD");

        // plastic: one seal
        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.refill(wh, "PL-" + N, "SRC-" + N, 20_000, "", null)).getMessage().contains("one seal"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.refill(wh, "PL-" + N, "SRC-" + N, 20_000, null, null)).getMessage().contains("seal"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.refill(wh, "PL-" + N, "SRC-" + N, 20_000, "P1" + N, "P2" + N)).getMessage().contains("only one seal"));
        // metal: two, and not the same
        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.refill(wh, "ME-" + N, "SRC-" + N, 17_000, "M1" + N, "")).getMessage().contains("both seal numbers"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> containers.refill(wh, "ME-" + N, "SRC-" + N, 17_000, "M1" + N, "M1" + N)).getMessage().contains("different numbers"));
        // nothing moved while the seals were wrong
        assertEquals(900_000, containers.find("SRC-" + N).orElseThrow().currentVolumeMl(), 0.0);
        assertEquals("EMPTY", containers.find("PL-" + N).orElseThrow().status());

        containers.refill(wh, "PL-" + N, "SRC-" + N, 20_000, "P1" + N, null);
        Container pl = containers.find("PL-" + N).orElseThrow();
        assertEquals("SEALED", pl.status());
        assertEquals("P1" + N, pl.topSeal());
        assertNull(pl.bottomSeal());
        assertEquals(20_000, pl.currentVolumeMl(), 0.0);
        containers.refill(wh, "ME-" + N, "SRC-" + N, 17_000, "M1" + N, "M2" + N);
        Container me = containers.find("ME-" + N).orElseThrow();
        assertEquals("M1" + N, me.topSeal());
        assertEquals("M2" + N, me.bottomSeal());
        assertEquals(900_000 - 37_000, containers.find("SRC-" + N).orElseThrow().currentVolumeMl(), 0.0);

        // a seal number is on one container only
        Fixtures.register("PL2-" + N, "JERRICAN_PLASTIC", 22_000, "STANDARD");
        containers.receiveEmptyJerrican(wh, site, "PL2-" + N, "STANDARD");
        String reused = assertThrows(IllegalStateException.class, () -> containers.refill(wh, "PL2-" + N, "SRC-" + N, 1_000, "M2" + N, null)).getMessage();
        assertTrue(reused.contains("already on container ME-" + N), reused);
        assertEquals("EMPTY", containers.find("PL2-" + N).orElseThrow().status());

        // a filled jerrican is not refilled again; only the warehouse manager refills
        assertTrue(assertThrows(IllegalStateException.class, () -> containers.refill(wh, "PL-" + N, "SRC-" + N, 1_000, "P9" + N, null)).getMessage().contains("only an EMPTY jerrican"));
        assertThrows(IllegalStateException.class, () -> containers.refill(sup, "PL2-" + N, "SRC-" + N, 1_000, "P8" + N, null));
    }

    @Test @Order(4)
    void theRumInTheIbcMustBeTheRumTheJerricanIsRegisteredFor() {
        String ibcB = "SRCB-" + N;
        containers.receiveBulk(wh, site, ibcB, "IBC", 1_000_000, 500_000, rumB(), "T", "B");
        Fixtures.register("RM-" + N, "JERRICAN_PLASTIC", 22_000, "STANDARD");
        containers.receiveEmptyJerrican(wh, site, "RM-" + N, "STANDARD");
        String msg = assertThrows(IllegalStateException.class, () -> containers.refill(wh, "RM-" + N, ibcB, 1_000, "RMS" + N, null)).getMessage();
        assertTrue(msg.contains("Rum type mismatch") && msg.contains(rumB()) && msg.contains("STANDARD"), msg);
        assertEquals(500_000, containers.find(ibcB).orElseThrow().currentVolumeMl(), 0.0);

        // an IBC needs a rum type from the master list, and jerricans do not come in this way
        assertThrows(IllegalArgumentException.class, () -> containers.receiveBulk(wh, site, "NOR-" + N, "IBC", 1_000_000, 0, null, "T", "B"));
        assertThrows(IllegalArgumentException.class, () -> containers.receiveBulk(wh, site, "BAD-" + N, "IBC", 1_000_000, 0, "NOPE", "T", "B"));
        assertThrows(IllegalArgumentException.class, () -> containers.receiveBulk(wh, site, "JAS-" + N, "JERRICAN_PLASTIC", 22_000, 0, "STANDARD", "T", null));
        assertEquals("STANDARD", containers.find("SRC-" + N).orElseThrow().rumTypeCode());
    }

    @Test @Order(5)
    void openingChecksTheSealsTheJerricanReallyHas() {
        Shift shift = new ShiftService().start(sup, site);
        // PL-: plastic with the one seal P1<N>; ME-: metal with M1<N> and M2<N>
        assertThrows(IllegalStateException.class, () -> containers.openJerrican(sup, site, shift, "PL-" + N, "P1" + N + "X", "", SIG));
        assertThrows(IllegalArgumentException.class, () -> containers.openJerrican(sup, site, shift, "PL-" + N, "P1" + N, "P2" + N, SIG), "a plastic jerrican has no second seal");
        assertEquals("SEALED", containers.find("PL-" + N).orElseThrow().status());
        assertEquals("OPENED", containers.openJerrican(sup, site, shift, "PL-" + N, "P1" + N, "", SIG).status());

        assertThrows(IllegalArgumentException.class, () -> containers.openJerrican(sup, site, shift, "ME-" + N, "M1" + N, "", SIG), "both seals are checked on a metal jerrican");
        assertThrows(IllegalStateException.class, () -> containers.openJerrican(sup, site, shift, "ME-" + N, "M2" + N, "M1" + N, SIG), "swapped seals do not match");
        assertEquals("OPENED", containers.openJerrican(sup, site, shift, "ME-" + N, "M1" + N, "M2" + N, SIG).status());
    }

    @Test @Order(6)
    void everyCompartmentCarriesItsOwnFuelAndEachFuelIsMarkedWithItsOwnRumType() {
        MarkingService markings = new MarkingService();
        var master = new MasterDataDao();
        Shift shift = Tx.call(c -> new org.jai.fuelmarking.dao.ShiftDao().findOpen(c, site.id())).orElseThrow();
        // kerosene is marked with rum B, diesel with the standard rum (head office says so; the desktop is told with the fuel type)
        apply(message("FUEL_TYPE", "{\"id\":\"f0000000-0000-4000-8000-000000000003\",\"code\":\"KEROSENE\",\"name\":\"Kerosene\",\"rumTypeCode\":\"" + rumB() + "\",\"active\":true}"));
        try {
            assertEquals(rumB(), Tx.call(c -> master.fuelType(c, "KEROSENE")).orElseThrow().rumTypeCode());
            assertEquals("STANDARD", Tx.call(c -> master.fuelType(c, "DIESEL")).orElseThrow().rumTypeCode());

            // refused: unknown fuel, no compartments, a compartment without a fuel, fuel without a rum type, fuel whose rum type is gone
            assertThrows(IllegalArgumentException.class, () -> markings.register(sup, site, shift, omc, "T1-" + N, "WATER", 10_000, "STANDARD", List.of(), SIG), "unknown fuel type");
            assertThrows(IllegalArgumentException.class, () -> markings.register(sup, site, shift, omc, "T1-" + N, null, List.of(), SIG), "a truck has compartments");
            assertTrue(assertThrows(IllegalArgumentException.class, () -> markings.register(sup, site, shift, omc, "T1-" + N, null,
                    List.of(new CompartmentDetail("C1", 5000, 5000, "", null)), SIG)).getMessage().contains("fuel type of compartment C1"));
            assertTrue(assertThrows(IllegalStateException.class, () -> markings.register(sup, site, shift, omc, "T1-" + N, "DIESEL", 10_000, rumB(), List.of(), SIG)).getMessage().contains("not " + rumB()),
                    "the rum type is the fuel's, not the caller's choice");
            Tx.run(c -> { master.setFuelRum(c, "PETROL", null); return null; });
            assertTrue(assertThrows(IllegalStateException.class, () -> markings.register(sup, site, shift, omc, "T1-" + N, "PETROL", 10_000, null, List.of(), SIG)).getMessage().contains("has no rum type yet"));
            Tx.run(c -> { master.setFuelRum(c, "PETROL", "NOPE"); return null; });
            assertTrue(assertThrows(IllegalStateException.class, () -> markings.register(sup, site, shift, omc, "T1-" + N, "PETROL", 10_000, null, List.of(), SIG)).getMessage().contains("is not available"));
            Tx.run(c -> { master.setFuelRum(c, "PETROL", "STANDARD"); return null; });

            // a truck with only kerosene: the jerrican of the standard rum is the wrong one
            TruckMarking onB = markings.register(sup, site, shift, omc, "T2-" + N, "KEROSENE", 10_000, rumB(), List.of(), SIG);
            assertEquals("KEROSENE", onB.getFuelType());
            assertEquals(rumB(), onB.getRumType());
            String msg = assertThrows(IllegalStateException.class, () -> markings.dose(dosing, onB.getUuid(), "PL-" + N, 100.0, "SCALE", SIG)).getMessage();
            assertTrue(msg.contains("holds rum type STANDARD") && msg.contains(rumB()), msg);

            // a truck with only diesel: one dose of the standard rum, and it is done
            TruckMarking onStd = markings.register(sup, site, shift, omc, "T3-" + N, "DIESEL", 10_000, "STANDARD", List.of(), SIG);
            assertEquals(100.0, markings.dose(dosing, onStd.getUuid(), "PL-" + N, 100.0, "SCALE", SIG), 0.0);
            assertEquals(MarkingStatus.DOSED, markings.byShift(shift.id(), MarkingStatus.DOSED).stream().filter(x -> x.getUuid().equals(onStd.getUuid())).findFirst().orElseThrow().getStatus());

            // diesel in one compartment and kerosene in another: two rum types, so two doses - the truck is dosed when both are in
            Fixtures.register("PB-" + N, "JERRICAN_PLASTIC", 22_000, rumB());
            containers.receiveEmptyJerrican(wh, site, "PB-" + N, rumB());
            containers.refill(wh, "PB-" + N, "SRCB-" + N, 2_000, "PBS" + N, null);
            containers.openJerrican(sup, site, shift, "PB-" + N, "PBS" + N, "", SIG);
            TruckMarking mixed = markings.register(sup, site, shift, omc, "T4-" + N, "x",
                    List.of(new CompartmentDetail("C1", 6000, 6000, "DIESEL", null), new CompartmentDetail("C2", 4000, 4000, "KEROSENE", null)), SIG);
            assertEquals("DIESEL+KEROSENE", mixed.getFuelType());
            assertEquals("STANDARD+" + rumB(), mixed.getRumType());
            assertEquals(10_000, mixed.getDeclaredVolume(), 0.0, "the declared volume is what is loaded in all compartments");
            assertEquals(List.of("STANDARD", rumB()), List.copyOf(mixed.getRequiredByRum().keySet()));
            assertEquals(60.0, mixed.getRequiredByRum().get("STANDARD"), 0.001);
            assertEquals(40.0, mixed.getRequiredByRum().get(rumB()), 0.001);
            assertEquals(100.0, mixed.calculateRequiredRum(), 0.001);

            // each compartment is dosed on its own, against its own target, from a jerrican of its own rum type
            assertEquals(2, mixed.getRequiredDoses().size());
            assertEquals(60.0, mixed.getRequiredDoses().get(0).requiredG(), 0.001, "C1: 6 000 L of diesel");
            assertEquals(40.0, mixed.getRequiredDoses().get(1).requiredG(), 0.001, "C2: 4 000 L of kerosene");
            assertTrue(assertThrows(IllegalArgumentException.class, () -> markings.dose(dosing, mixed.getUuid(), "C1", "PL-" + N, 100.0, "SCALE", SIG)).getMessage().contains("compartment C1 needs 60.0 g"),
                    "the dose is checked against the compartment's own target, not the truck's");
            assertTrue(assertThrows(IllegalStateException.class, () -> markings.dose(dosing, mixed.getUuid(), "C1", "PB-" + N, 60.0, "SCALE", SIG)).getMessage().contains("compartment C1 (DIESEL) is to be marked with STANDARD"),
                    "the jerrican must hold the rum type of that compartment's fuel");
            assertTrue(assertThrows(IllegalArgumentException.class, () -> markings.dose(dosing, mixed.getUuid(), "C9", "PL-" + N, 60.0, "SCALE", SIG)).getMessage().contains("no compartment C9"));
            assertEquals(40.0, markings.dose(dosing, mixed.getUuid(), "C2", "PB-" + N, 40.0, "SCALE", SIG), 0.0, "any order: C2 first");
            TruckMarking half = markings.byShift(shift.id(), null).stream().filter(x -> x.getUuid().equals(mixed.getUuid())).findFirst().orElseThrow();
            assertEquals(MarkingStatus.REGISTERED, half.getStatus(), "C1 is still to dose");
            assertEquals(List.of("C1"), half.remainingDoses().stream().map(RequiredDose::compartment).toList());
            assertTrue(assertThrows(IllegalStateException.class, () -> markings.dose(dosing, mixed.getUuid(), "C2", "PB-" + N, 40.0, "SCALE", SIG)).getMessage().contains("Compartment C2 is already dosed"));
            assertThrows(IllegalStateException.class, () -> markings.dispense(new AppUser(UUID.randomUUID(), "d", "d", Role.DISPENSE_OPERATOR, site.id()), mixed.getUuid(), SIG), "not fully dosed yet");
            assertEquals(60.5, markings.dose(dosing, mixed.getUuid(), "C1", "PL-" + N, 60.5, "MANUAL", SIG), 0.0);
            TruckMarking full = markings.byShift(shift.id(), null).stream().filter(x -> x.getUuid().equals(mixed.getUuid())).findFirst().orElseThrow();
            assertEquals(MarkingStatus.DOSED, full.getStatus());
            assertEquals(2, full.getDoses().size());
            assertEquals("C2", full.getDoses().get(0).compartment());
            assertEquals(100.5, full.getDoseMeasuredG(), 0.001);
            assertEquals("MANUAL", full.getDoseSource(), "one of the doses was typed in");
            var breakdown = full.getFuelBreakdown();
            assertEquals(2, breakdown.size());
            assertEquals("DIESEL", breakdown.get(0).get("fuelType"));
            assertEquals(60.5, (double) breakdown.get(0).get("rumG"), 0.001, "each fuel carries the rum weighed for its own compartment");
            assertEquals(40.0, (double) breakdown.get(1).get("rumG"), 0.001);

            // two compartments of the same fuel are still dosed separately - and in the order the operator likes
            TruckMarking twin = markings.register(sup, site, shift, omc, "T5-" + N, "x",
                    List.of(new CompartmentDetail("C1", 3000, 3000, "DIESEL", null), new CompartmentDetail("C2", 7000, 7000, "DIESEL", null)), SIG);
            assertEquals(30.0, twin.getRequiredDoses().get(0).requiredG(), 0.001);
            assertEquals(70.0, twin.getRequiredDoses().get(1).requiredG(), 0.001);
            assertThrows(IllegalArgumentException.class, () -> markings.dose(dosing, twin.getUuid(), "C1", "PL-" + N, 70.0, "SCALE", SIG), "70 g is C2's target, not C1's");
            assertEquals(70.0, markings.dose(dosing, twin.getUuid(), "C2", "PL-" + N, 70.0, "SCALE", SIG), 0.0);
            // without naming a compartment the first one still to dose that the rum fits is taken (kept for older callers)
            assertEquals(30.0, markings.dose(dosing, twin.getUuid(), "PL-" + N, 30.0, "SCALE", SIG), 0.0);
            assertTrue(assertThrows(IllegalStateException.class, () -> markings.dose(dosing, twin.getUuid(), "PL-" + N, 30.0, "SCALE", SIG)).getMessage().contains("is DOSED"), "all compartments done: nothing more to dose");
            int queued = Tx.call(c -> {
                try (var ps = c.prepareStatement("SELECT count(*) FROM sync_outbox WHERE entity_type='TRUCK_MARKING' AND entity_id=? AND payload::text LIKE '%fuelBreakdown%'")) {
                    ps.setString(1, mixed.getUuid().toString());
                    try (var rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
                }
            });
            assertEquals(3, queued, "registration and both compartment doses were queued, each with the breakdown for head office");
        } finally {
            apply(message("FUEL_TYPE", "{\"id\":\"f0000000-0000-4000-8000-000000000003\",\"code\":\"KEROSENE\",\"name\":\"Kerosene\",\"rumTypeCode\":\"STANDARD\",\"active\":true}"));
        }
    }
}
