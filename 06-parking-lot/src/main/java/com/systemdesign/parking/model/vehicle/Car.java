package com.systemdesign.parking.model.vehicle;

import com.systemdesign.parking.model.VehicleType;

/**
 * Concrete vehicle: Car.
 * Can park in Compact, Large, or Handicapped spots.
 */
public class Car extends Vehicle {

    public Car(String licensePlate) {
        super(licensePlate, VehicleType.CAR);
    }
}
