package com.systemdesign.parking.model;

/**
 * Enum representing types of parking spots.
 * Spot types define physical size and accessibility constraints.
 */
public enum SpotType {
    MOTORCYCLE_SPOT("Motorcycle"),
    COMPACT("Compact"),
    LARGE("Large"),
    HANDICAPPED("Handicapped");

    private final String displayName;

    SpotType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
