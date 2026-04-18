package com.systemdesign.parking.model.spot;

import com.systemdesign.parking.model.SpotType;
import com.systemdesign.parking.model.VehicleType;
import com.systemdesign.parking.model.vehicle.Vehicle;

/**
 * Motorcycle-only parking spot: fits only Motorcycles.
 * The most restrictive spot type in the hierarchy.
 */
public class MotorcycleSpot extends ParkingSpot {

    public MotorcycleSpot(String spotId, int floorNumber, int spotNumber) {
        super(spotId, floorNumber, spotNumber, SpotType.MOTORCYCLE_SPOT);
    }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getVehicleType() == VehicleType.MOTORCYCLE;
    }
}
