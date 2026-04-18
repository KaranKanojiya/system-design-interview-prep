package com.systemdesign.parking.model.vehicle;

import com.systemdesign.parking.model.VehicleType;

/**
 * Concrete vehicle: Motorcycle.
 * Can park in Motorcycle, Compact, or Large spots (smallest vehicle fits anywhere).
 */
public class Motorcycle extends Vehicle {

    public Motorcycle(String licensePlate) {
        super(licensePlate, VehicleType.MOTORCYCLE);
    }
}
