// src/main/java/org/jai/fuelmarking/model/TruckMarking.java
package org.jai.fuelmarking.model;

import org.jai.fuelmarking.config.YamlConfigurationLoader;

import java.time.LocalDateTime;
import java.util.*;

public class TruckMarking {
    private Long id;
    private String orderNumber;
    private String oilMarketingCompany;
    private String truckNumber;
    private String trailerNumber;      // trailer or wagon pulled by the truck; optional
    private List<CompartmentDetail> compartments;
    private String fuelType;           // the fuel(s) on the truck: one code, or several joined by "+"
    private double declaredVolume;
    private MarkingStatus status;
    private LocalDateTime registeredTime;
    private LocalDateTime dosedTime;
    private LocalDateTime dispensedTime;
    private LocalDateTime completedTime;
    private LocalDateTime deletedTime;       // set when the marking was deleted (before its first dose)
    private double rumUsed;
    private UUID uuid = UUID.randomUUID();
    private UUID siteId;
    private UUID shiftId;
    private UUID omcId;
    private String rumType;
    private double doseCalculatedMl;
    private UUID doseContainerId;
    private Double doseMeasuredG;   // what was actually weighed; null until dosed
    private String doseSource;      // SCALE or MANUAL (MANUAL if any dose was typed in)
    private List<org.jai.fuelmarking.model.Records.MarkingDose> doses = new ArrayList<>();   // one per rum type, as they are recorded
    
    // Constructors
    public TruckMarking() {
        this.status = MarkingStatus.REGISTERED;
        this.registeredTime = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    
    public String getOilMarketingCompany() { return oilMarketingCompany; }
    public void setOilMarketingCompany(String oilMarketingCompany) { this.oilMarketingCompany = oilMarketingCompany; }
    
    public String getTruckNumber() { return truckNumber; }
    public void setTruckNumber(String truckNumber) { this.truckNumber = truckNumber; }
    
    public String getTrailerNumber() { return trailerNumber; }
    public void setTrailerNumber(String trailerNumber) { this.trailerNumber = trailerNumber; }

    public List<CompartmentDetail> getCompartments() { return compartments; }
    public void setCompartments(List<CompartmentDetail> compartments) { this.compartments = compartments; }
    
    public String getFuelType() { return fuelType; }
    public void setFuelType(String fuelType) { this.fuelType = fuelType; }
    
    public double getDeclaredVolume() { return declaredVolume; }
    public void setDeclaredVolume(double declaredVolume) { this.declaredVolume = declaredVolume; }
    
    public MarkingStatus getStatus() { return status; }
    public void setStatus(MarkingStatus status) { this.status = status; }
    
    public LocalDateTime getRegisteredTime() { return registeredTime; }
    public void setRegisteredTime(LocalDateTime registeredTime) { this.registeredTime = registeredTime; }
    
    public LocalDateTime getDosedTime() { return dosedTime; }
    public void setDosedTime(LocalDateTime dosedTime) { this.dosedTime = dosedTime; }
    
    public LocalDateTime getDispensedTime() { return dispensedTime; }
    public void setDispensedTime(LocalDateTime dispensedTime) { this.dispensedTime = dispensedTime; }
    
    public LocalDateTime getCompletedTime() { return completedTime; }
    public void setCompletedTime(LocalDateTime completedTime) { this.completedTime = completedTime; }
    
    public LocalDateTime getDeletedTime() { return deletedTime; }
    public void setDeletedTime(LocalDateTime deletedTime) { this.deletedTime = deletedTime; }

    public double getRumUsed() { return rumUsed; }
    public void setRumUsed(double rumUsed) { this.rumUsed = rumUsed; }
    
    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }

    public UUID getShiftId() { return shiftId; }
    public void setShiftId(UUID shiftId) { this.shiftId = shiftId; }

    public UUID getOmcId() { return omcId; }
    public void setOmcId(UUID omcId) { this.omcId = omcId; }

    public String getRumType() { return rumType; }
    public void setRumType(String rumType) { this.rumType = rumType; }

    public double getDoseCalculatedMl() { return doseCalculatedMl; }
    public void setDoseCalculatedMl(double doseCalculatedMl) { this.doseCalculatedMl = doseCalculatedMl; }

    public UUID getDoseContainerId() { return doseContainerId; }
    public void setDoseContainerId(UUID doseContainerId) { this.doseContainerId = doseContainerId; }

    public Double getDoseMeasuredG() { return doseMeasuredG; }
    public void setDoseMeasuredG(Double doseMeasuredG) { this.doseMeasuredG = doseMeasuredG; }

    public String getDoseSource() { return doseSource; }
    public void setDoseSource(String doseSource) { this.doseSource = doseSource; }

    public List<org.jai.fuelmarking.model.Records.MarkingDose> getDoses() { return doses; }
    public void setDoses(List<org.jai.fuelmarking.model.Records.MarkingDose> doses) { this.doses = doses == null ? new ArrayList<>() : doses; }

    /**
     * The doses the truck needs - one for each compartment: its litres over 1000 times the configured dosage, of the rum type its fuel is
     * marked with. A truck recorded without compartment fuels needs a single dose for the whole truck.
     */
    public List<org.jai.fuelmarking.model.Records.RequiredDose> getRequiredDoses() {
        double per1000 = YamlConfigurationLoader.FuelMarking.getRumDosagePer1000L();
        List<org.jai.fuelmarking.model.Records.RequiredDose> out = new ArrayList<>();
        boolean anyFuel = compartments != null && compartments.stream().anyMatch(d -> d.getRumType() != null);
        if (anyFuel) {
            for (CompartmentDetail d : compartments)
                if (d.getRumType() != null)
                    out.add(new org.jai.fuelmarking.model.Records.RequiredDose(d.getCompartmentNumber(), d.getFuelType(), d.getRumType(), d.getActualVolume(),
                            d.getActualVolume() / 1000.0 * per1000));
        } else {
            out.add(new org.jai.fuelmarking.model.Records.RequiredDose("ALL", fuelType, rumType == null ? "STANDARD" : rumType, declaredVolume, declaredVolume / 1000.0 * per1000));
        }
        return out;
    }

    /** The rum each rum type has to supply, in grams, over all compartments that carry a fuel marked with it. */
    public Map<String, Double> getRequiredByRum() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (var r : getRequiredDoses()) out.merge(r.rumType(), r.requiredG(), Double::sum);
        return out;
    }

    /** Total rum the truck needs, over all its compartments. */
    public double calculateRequiredRum() {
        return getRequiredDoses().stream().mapToDouble(org.jai.fuelmarking.model.Records.RequiredDose::requiredG).sum();
    }

    /** The compartments that still have to be dosed. */
    public List<org.jai.fuelmarking.model.Records.RequiredDose> remainingDoses() {
        Set<String> done = new HashSet<>();
        for (var d : doses) done.add(d.compartment());
        List<org.jai.fuelmarking.model.Records.RequiredDose> out = new ArrayList<>();
        for (var r : getRequiredDoses()) if (!done.contains(r.compartment())) out.add(r);
        return out;
    }

    /** The rum types of the compartments that still have to be dosed. */
    public List<String> remainingRumTypes() {
        List<String> out = new ArrayList<>();
        for (var r : remainingDoses()) if (!out.contains(r.rumType())) out.add(r.rumType());
        return out;
    }

    /**
     * Litres and rum per fuel, for reports: each fuel's litres, the rum type it is marked with, and the rum that was weighed for its compartments.
     * Older records without compartment fuels count as one fuel.
     */
    public List<Map<String, Object>> getFuelBreakdown() {
        boolean anyFuel = compartments != null && compartments.stream().anyMatch(d -> d.getFuelType() != null);
        Map<String, double[]> byFuel = new LinkedHashMap<>();         // fuel -> {litres, rum grams}
        Map<String, String> rumOf = new LinkedHashMap<>();
        Map<String, String> fuelOfCompartment = new HashMap<>();
        if (anyFuel) {
            for (CompartmentDetail d : compartments) {
                if (d.getFuelType() == null) continue;
                byFuel.computeIfAbsent(d.getFuelType(), k -> new double[2])[0] += d.getActualVolume();
                rumOf.putIfAbsent(d.getFuelType(), d.getRumType());
                fuelOfCompartment.put(d.getCompartmentNumber(), d.getFuelType());
            }
        } else {
            String fuel = fuelType == null ? "UNKNOWN" : fuelType;
            byFuel.put(fuel, new double[]{declaredVolume, 0});
            rumOf.put(fuel, rumType);
            fuelOfCompartment.put("ALL", fuel);
        }
        for (var d : doses) {
            String fuel = fuelOfCompartment.getOrDefault(d.compartment(), byFuel.keySet().iterator().next());
            byFuel.get(fuel)[1] += d.measuredG();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : byFuel.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fuelType", e.getKey()); row.put("rumType", rumOf.get(e.getKey())); row.put("litres", e.getValue()[0]);
            row.put("rumG", Math.round(e.getValue()[1] * 100.0) / 100.0);
            out.add(row);
        }
        return out;
    }
}
