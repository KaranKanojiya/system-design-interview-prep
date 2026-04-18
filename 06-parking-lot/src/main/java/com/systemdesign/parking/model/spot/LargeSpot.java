package com.systemdesign.parking.model.spot;

import com.systemdesign.parking.model.SpotType;
import com.systemdesign.parking.model.vehicle.Vehicle;

/**
 * Large parking spot: fits all vehicle types (Bus, Car, Motorcycle).
 * The most permissive spot type in the hierarchy.
 */
public class LargeSpot extends ParkingSpot {

    public LargeSpot(String spotId, int floorNumber, int spotNumber) {
        super(spotId, floorNumber, spotNumber, SpotType.LARGE);
    }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        // Large spots accept every vehicle type
        return true;
    }
}
