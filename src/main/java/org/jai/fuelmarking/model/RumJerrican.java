// src/main/java/org/jai/fuelmarking/model/RumJerrican.java
package org.jai.fuelmarking.model;

import java.time.LocalDateTime;

public class RumJerrican {
    private Long id;
    private String serialNumber;
    private double weight;
    private String topSeal;
    private String bottomSeal;
    private LocalDateTime receivedDate;
    private boolean opened;
    private double remainingVolume;
    
    // Constructors
    public RumJerrican() {}
    
    public RumJerrican(String serialNumber, double weight, String topSeal, String bottomSeal) {
        this.serialNumber = serialNumber;
        this.weight = weight;
        this.topSeal = topSeal;
        this.bottomSeal = bottomSeal;
        this.receivedDate = LocalDateTime.now();
        this.opened = false;
        this.remainingVolume = 1000.0; // Assuming 1L capacity
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    
    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }
    
    public String getTopSeal() { return topSeal; }
    public void setTopSeal(String topSeal) { this.topSeal = topSeal; }
    
    public String getBottomSeal() { return bottomSeal; }
    public void setBottomSeal(String bottomSeal) { this.bottomSeal = bottomSeal; }
    
    public LocalDateTime getReceivedDate() { return receivedDate; }
    public void setReceivedDate(LocalDateTime receivedDate) { this.receivedDate = receivedDate; }
    
    public boolean isOpened() { return opened; }
    public void setOpened(boolean opened) { this.opened = opened; }
    
    public double getRemainingVolume() { return remainingVolume; }
    public void setRemainingVolume(double remainingVolume) { this.remainingVolume = remainingVolume; }
}
