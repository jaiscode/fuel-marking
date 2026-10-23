// src/main/java/org/jai/fuelmarking/model/MarkingStatus.java
package org.jai.fuelmarking.model;

public enum MarkingStatus {
    REGISTERED("Registered"),
    DOSED("Dosed"),
    DISPENSED("Dispensed"),
    COMPLETED("Completed"),
    /** Deleted before its first dose; kept on record but counted nowhere. */
    DELETED("Deleted");

    private final String displayName;

    MarkingStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
