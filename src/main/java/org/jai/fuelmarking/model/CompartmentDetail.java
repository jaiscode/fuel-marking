package org.jai.fuelmarking.model;

/** One compartment of a truck: its capacity, what is loaded, and which fuel - and so which rum type - it carries. */
public class CompartmentDetail {
    private String compartmentNumber;
    private double capacity;
    private double actualVolume;
    private String fuelType;     // code of a fuel type from the master data
    private String rumType;      // the rum type that fuel is marked with (filled in when the truck is registered)

    public CompartmentDetail() {}

    public CompartmentDetail(String compartmentNumber, double capacity, double actualVolume) {
        this(compartmentNumber, capacity, actualVolume, null, null);
    }

    public CompartmentDetail(String compartmentNumber, double capacity, double actualVolume, String fuelType, String rumType) {
        this.compartmentNumber = compartmentNumber;
        this.capacity = capacity;
        this.actualVolume = actualVolume;
        this.fuelType = fuelType;
        this.rumType = rumType;
    }

    public String getCompartmentNumber() { return compartmentNumber; }
    public void setCompartmentNumber(String compartmentNumber) { this.compartmentNumber = compartmentNumber; }

    public double getCapacity() { return capacity; }
    public void setCapacity(double capacity) { this.capacity = capacity; }

    public double getActualVolume() { return actualVolume; }
    public void setActualVolume(double actualVolume) { this.actualVolume = actualVolume; }

    public String getFuelType() { return fuelType; }
    public void setFuelType(String fuelType) { this.fuelType = fuelType; }

    public String getRumType() { return rumType; }
    public void setRumType(String rumType) { this.rumType = rumType; }
}
