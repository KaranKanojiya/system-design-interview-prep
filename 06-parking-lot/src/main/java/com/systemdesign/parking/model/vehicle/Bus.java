package com.systemdesign.parking.model.vehicle;

import com.systemdesign.parking.model.VehicleType;

/**
 * Concrete vehicle: Bus (or Truck).
 * Can only park in Large spots due to size.
 */
public class Bus extends Vehicle {

    public Bus(String licensePlate) {
        super(licensePlate, VehicleType.BUS);
    }
}
