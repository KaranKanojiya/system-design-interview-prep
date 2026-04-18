package com.systemdesign.parking.model.vehicle;

import com.systemdesign.parking.model.VehicleType;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Abstract base class for all vehicle types in the parking system.
 *
 * Demonstrates:
 * - Template Method pattern: subclasses define their VehicleType
 * - Liskov Substitution Principle: any Vehicle subclass can be used interchangeably
 * - Open/Closed Principle: new vehicle types added by extending, not modifying
 *
 * Protected constructor ensures only concrete subclasses can instantiate.
 */
public abstract class Vehicle {

    private final String licensePlate;
    private final VehicleType vehicleType;
    private final LocalDateTime entryTime;

    protected Vehicle(String licensePlate, VehicleType vehicleType) {
        this.licensePlate = Objects.requireNonNull(licensePlate, "License plate cannot be null");
        this.vehicleType = Objects.requireNonNull(vehicleType, "Vehicle type cannot be null");
        this.entryTime = LocalDateTime.now();
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public LocalDateTime getEntryTime() {
        return entryTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Vehicle vehicle = (Vehicle) o;
        return licensePlate.equals(vehicle.licensePlate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(licensePlate);
    }

    @Override
    public String toString() {
        return "[" + vehicleType.name() + "] " + licensePlate;
    }
}
