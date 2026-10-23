package org.jai.fuelmarking.service;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.config.YamlConfigurationLoader.FuelMarking;
import org.jai.fuelmarking.dao.*;
import org.jai.fuelmarking.model.*;
import org.jai.fuelmarking.model.Records.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** The register -> dose -> dispense -> verify workflow. */
public class MarkingService {
    private final TruckMarkingDao markings = new TruckMarkingDao();
    private final ContainerDao containers = new ContainerDao();
    private final SignatureDao signatures = new SignatureDao();
    private final MasterDataDao masterData = new MasterDataDao();

    /**
     * One fuel for the whole truck, kept for convenience (and older callers): the compartments, if there are any, carry that fuel; with none, the
     * truck is one compartment holding the declared volume. {@code rumType} must be the rum type the fuel is marked with.
     */
    public TruckMarking register(AppUser supervisor, Site site, Shift shift, Omc omc, String truckNumber,
                                 String fuelTypeCode, double declaredVolume, String rumType,
                                 List<CompartmentDetail> compartments, byte[] signature) {
        return register(supervisor, site, shift, omc, truckNumber, null, fuelTypeCode, declaredVolume, rumType, compartments, signature);
    }

    public TruckMarking register(AppUser supervisor, Site site, Shift shift, Omc omc, String truckNumber, String trailerNumber,
                                 String fuelTypeCode, double declaredVolume, String rumType,
                                 List<CompartmentDetail> compartments, byte[] signature) {
        if (fuelTypeCode == null || fuelTypeCode.isBlank()) throw new IllegalArgumentException("Select the fuel type");
        List<CompartmentDetail> comps = new ArrayList<>();
        if (compartments == null || compartments.isEmpty()) {
            comps.add(new CompartmentDetail("C1", declaredVolume, declaredVolume, fuelTypeCode, null));
        } else {
            double total = compartments.stream().mapToDouble(CompartmentDetail::getActualVolume).sum();
            if (Math.abs(total - declaredVolume) > 0.5)
                throw new IllegalArgumentException("Compartment volumes (" + total + ") do not add up to declared volume");
            for (CompartmentDetail d : compartments)
                comps.add(new CompartmentDetail(d.getCompartmentNumber(), d.getCapacity(), d.getActualVolume(), d.getFuelType() == null ? fuelTypeCode : d.getFuelType(), null));
        }
        TruckMarking t = register(supervisor, site, shift, omc, truckNumber, trailerNumber, comps, signature);
        if (rumType != null && !rumType.isBlank() && !t.getRequiredByRum().containsKey(rumType))
            throw new IllegalStateException("Fuel " + fuelTypeCode + " is marked with rum type " + t.getRumType() + ", not " + rumType);
        return t;
    }

    /**
     * Registers a truck by its compartments: each has a capacity, what is loaded, and the fuel it carries - and each fuel type is marked with one
     * particular rum type (set at head office), so the rum types the truck needs follow from its fuels. The declared volume is what is loaded in all.
     */
    public TruckMarking register(AppUser supervisor, Site site, Shift shift, Omc omc, String truckNumber, String trailerNumber,
                                 List<CompartmentDetail> compartments, byte[] signature) {
        Guard.require(supervisor, Role.SUPERVISOR);
        Guard.signed(signature);
        if (shift == null || !"OPEN".equals(shift.status())) throw new IllegalStateException("No open shift");
        if (truckNumber == null || truckNumber.isBlank()) throw new IllegalArgumentException("Truck number is required");
        if (compartments == null || compartments.isEmpty()) throw new IllegalArgumentException("Add at least one compartment");
        double declaredVolume = 0;
        for (CompartmentDetail d : compartments) {
            if (d.getCapacity() <= 0) throw new IllegalArgumentException("Compartment " + d.getCompartmentNumber() + " needs a capacity");
            if (d.getActualVolume() < 0) throw new IllegalArgumentException("Compartment " + d.getCompartmentNumber() + " has a negative volume");
            if (d.getActualVolume() > d.getCapacity())
                throw new IllegalArgumentException("Compartment " + d.getCompartmentNumber() + " exceeds its capacity");
            if (d.getFuelType() == null || d.getFuelType().isBlank())
                throw new IllegalArgumentException("Select the fuel type of compartment " + d.getCompartmentNumber());
            declaredVolume += d.getActualVolume();
        }
        if (declaredVolume < FuelMarking.getMinDeclaredVolume() || declaredVolume > FuelMarking.getMaxDeclaredVolume())
            throw new IllegalArgumentException("Declared volume must be between " + FuelMarking.getMinDeclaredVolume()
                    + " and " + FuelMarking.getMaxDeclaredVolume() + " L");
        final double declared = declaredVolume;

        TruckMarking t = new TruckMarking();
        String orderBase = site.code() + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        List<CompartmentDetail> stored = new ArrayList<>();
        return Tx.call(c -> {
            java.util.LinkedHashSet<String> fuels = new java.util.LinkedHashSet<>(), rums = new java.util.LinkedHashSet<>();
            for (CompartmentDetail d : compartments) {
                var fuel = masterData.fuelType(c, d.getFuelType()).orElseThrow(() -> new IllegalArgumentException("Unknown fuel type " + d.getFuelType()));
                if (fuel.rumTypeCode() == null || fuel.rumTypeCode().isBlank())
                    throw new IllegalStateException("Fuel type " + fuel.name() + " has no rum type yet - head office sets it in the web app");
                if (masterData.rumType(c, fuel.rumTypeCode()).isEmpty())
                    throw new IllegalStateException("Rum type " + fuel.rumTypeCode() + " of fuel " + fuel.name() + " is not available");
                stored.add(new CompartmentDetail(d.getCompartmentNumber(), d.getCapacity(), d.getActualVolume(), fuel.code(), fuel.rumTypeCode()));
                fuels.add(fuel.code());
                rums.add(fuel.rumTypeCode());
            }
            t.setOilMarketingCompany(omc.name());
            t.setOmcId(omc.id()); t.setSiteId(site.id()); t.setShiftId(shift.id());
            t.setTruckNumber(truckNumber.trim().toUpperCase());
            t.setTrailerNumber(trailerNumber == null || trailerNumber.isBlank() ? null : trailerNumber.trim().toUpperCase());
            t.setFuelType(String.join("+", fuels)); t.setRumType(String.join("+", rums));
            t.setDeclaredVolume(declared); t.setCompartments(stored);
            t.setOrderNumber(markings.uniqueOrderNumber(c, orderBase));
            markings.insert(c, t, omc.name(), supervisor.id());
            signatures.save(c, "TRUCK_MARKING", t.getUuid(), "REGISTRATION", supervisor.id(), signature);
            return t;
        });
    }

    /**
     * Records the dose that was actually weighed. The operator tares an empty container on the scale, draws rum from an
     * opened jerrican until the net weight matches the required dose (declared volume / 1000 x the configured dose), then
     * confirms. The weighed amount - not the calculated one - leaves the jerrican and is recorded, and it must be within
     * {@code fuel-marking.dose-tolerance-percent} of the requirement.
     *
     * @param source SCALE if the number was read from the scale, MANUAL if it was typed in (kept for audit)
     * @return the recorded dose in grams
     */
    public double dose(AppUser operator, UUID markingUuid, String jerricanBarcode, double measuredGrams, String source, byte[] signature) {
        return dose(operator, markingUuid, null, jerricanBarcode, measuredGrams, source, signature);
    }

    /**
     * Records the dose of ONE compartment: what was weighed for it, drawn from an opened jerrican of the rum type its fuel is marked with, and within
     * the tolerance of that compartment's own target. When every compartment has been dosed the truck moves on to dispensing.
     *
     * @param compartment the compartment being dosed, e.g. "C2"; null picks the first compartment still to dose that this jerrican's rum type fits
     */
    public double dose(AppUser operator, UUID markingUuid, String compartment, String jerricanBarcode, double measuredGrams, String source, byte[] signature) {
        Guard.require(operator, Role.DOSING_OPERATOR);
        Guard.signed(signature);
        if (Double.isNaN(measuredGrams) || measuredGrams <= 0) throw new IllegalArgumentException("The measured dose must be more than zero");
        if (!"SCALE".equals(source) && !"MANUAL".equals(source)) throw new IllegalArgumentException("Dose source must be SCALE or MANUAL");
        return Tx.call(c -> {
            TruckMarking t = markings.findByUuid(c, markingUuid, true).orElseThrow(() -> new IllegalArgumentException("Unknown marking"));
            requireStatus(t, MarkingStatus.REGISTERED);
            Container k = containers.findByBarcode(c, jerricanBarcode, true).orElseThrow(() -> new IllegalArgumentException("Unknown jerrican"));
            if (!"OPENED".equals(k.status()) || k.type().isBulk() || !t.getSiteId().equals(k.siteId()))
                throw new IllegalStateException("Use an opened jerrican from this site");
            var all = t.getRequiredDoses();
            String jerryRum = k.rumTypeCode() != null ? k.rumTypeCode() : (all.stream().map(RequiredDose::rumType).distinct().count() == 1 ? all.get(0).rumType() : null);
            RequiredDose target;
            if (compartment != null && !compartment.isBlank()) {
                target = all.stream().filter(r -> r.compartment().equals(compartment)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("This truck has no compartment " + compartment));
            } else {                                         // no compartment named: the first one still to dose that this rum fits
                var left = t.remainingDoses();
                target = left.stream().filter(r -> r.rumType().equals(jerryRum)).findFirst().orElse(null);
                if (target == null) {
                    boolean fitsAny = all.stream().anyMatch(r -> r.rumType().equals(jerryRum));
                    if (fitsAny) throw new IllegalStateException("Every compartment that takes " + jerryRum + " is already dosed - still to dose: "
                            + left.stream().map(r -> r.compartment() + " (" + r.rumType() + ")").collect(java.util.stream.Collectors.joining(", ")));
                    throw new IllegalStateException("This jerrican holds rum type " + k.rumTypeCode() + " but this truck is to be marked with "
                            + String.join(" and ", all.stream().map(RequiredDose::rumType).distinct().toList()));
                }
            }
            if (t.getDoses().stream().anyMatch(d -> d.compartment().equals(target.compartment())))
                throw new IllegalStateException("Compartment " + target.compartment() + " is already dosed");
            if (!target.rumType().equals(jerryRum))
                throw new IllegalStateException("This jerrican holds rum type " + k.rumTypeCode() + " but compartment " + target.compartment() + " ("
                        + target.fuelType() + ") is to be marked with " + target.rumType());

            double required = target.requiredG();
            double allowed = required * FuelMarking.getDoseTolerancePercent() / 100.0;
            if (Math.abs(measuredGrams - required) > allowed + 1e-9)
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "The dose of %.1f g is outside the allowed range: compartment %s needs %.1f g of %s (+/- %.1f g). %s",
                        measuredGrams, target.compartment(), required, target.rumType(), allowed,
                        measuredGrams < required ? "Add more rum, then read the scale again." : "Too much rum was drawn - start again with a fresh container."));
            if (k.currentVolumeMl() < measuredGrams)
                throw new IllegalStateException(String.format(Locale.ROOT, "Jerrican holds %.1f g, need %.1f g", k.currentVolumeMl(), measuredGrams));

            containers.setVolume(c, k.id(), k.currentVolumeMl() - measuredGrams);
            markings.recordDose(c, t, target.compartment(), target.rumType(), k.id(), required, measuredGrams, source, operator.id());
            signatures.save(c, "TRUCK_MARKING", t.getUuid(), "DOSING", operator.id(), signature);
            return measuredGrams;
        });
    }

    public void dispense(AppUser operator, UUID markingUuid, byte[] signature) {
        Guard.require(operator, Role.DISPENSE_OPERATOR);
        Guard.signed(signature);
        Tx.run(c -> {
            TruckMarking t = markings.findByUuid(c, markingUuid, true).orElseThrow(() -> new IllegalArgumentException("Unknown marking"));
            requireStatus(t, MarkingStatus.DOSED);
            markings.recordDispense(c, t, operator.id());
            signatures.save(c, "TRUCK_MARKING", t.getUuid(), "DISPENSING", operator.id(), signature);
            return null;
        });
    }

    public void verify(AppUser supervisor, UUID markingUuid, byte[] signature) {
        Guard.require(supervisor, Role.SUPERVISOR);
        Guard.signed(signature);
        Tx.run(c -> {
            TruckMarking t = markings.findByUuid(c, markingUuid, true).orElseThrow(() -> new IllegalArgumentException("Unknown marking"));
            requireStatus(t, MarkingStatus.DISPENSED);
            markings.recordVerification(c, t, supervisor.id());
            signatures.save(c, "TRUCK_MARKING", t.getUuid(), "VERIFICATION", supervisor.id(), signature);
            return null;
        });
    }

    /**
     * Deletes a marking that was registered by mistake. It is only possible before the first dose: once any compartment has been dosed the rum
     * has left a jerrican, so the marking can no longer be deleted. The marking stays on record (who deleted it and when) but counts nowhere.
     */
    public void delete(AppUser supervisor, Site site, Shift shift, UUID markingUuid) {
        Guard.require(supervisor, Role.SUPERVISOR);
        if (shift == null || !"OPEN".equals(shift.status())) throw new IllegalStateException("Start a shift first");
        Tx.run(c -> {
            TruckMarking t = markings.findByUuid(c, markingUuid, true).orElseThrow(() -> new IllegalArgumentException("Unknown marking"));
            if (!site.id().equals(t.getSiteId())) throw new IllegalStateException("This marking belongs to another site");
            if (t.getStatus() == MarkingStatus.DELETED) throw new IllegalStateException("Marking " + t.getOrderNumber() + " is already deleted");
            if (!t.getDoses().isEmpty())
                throw new IllegalStateException("Truck " + t.getTruckNumber() + " has already been dosed (" + t.getDoses().size() + " of " + t.getRequiredDoses().size()
                        + " compartments) - a marking cannot be deleted once dosing has started");
            if (t.getStatus() != MarkingStatus.REGISTERED)
                throw new IllegalStateException("Marking " + t.getOrderNumber() + " is " + t.getStatus() + " - it can no longer be deleted");
            markings.recordDeletion(c, t, supervisor.id());
            return null;
        });
    }

    public List<TruckMarking> byShift(UUID shiftId, MarkingStatus status) {
        return Tx.call(c -> markings.listByShift(c, shiftId, status));
    }

    private static void requireStatus(TruckMarking t, MarkingStatus expected) {
        if (t.getStatus() != expected)
            throw new IllegalStateException("Marking " + t.getOrderNumber() + " is " + t.getStatus() + ", expected " + expected);
    }
}
