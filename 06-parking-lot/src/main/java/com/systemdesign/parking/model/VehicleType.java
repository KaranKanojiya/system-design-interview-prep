package com.systemdesign.parking.model;

/**
 * Enum representing types of vehicles that can be parked.
 * Each vehicle type maps to compatible spot types and pricing tiers.
 */
public enum VehicleType {
    MOTORCYCLE("Motorcycle"),
    CAR("Car"),
    BUS("Bus/Truck");

    private final String displayName;

    VehicleType(String displayName) {
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
