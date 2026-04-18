package com.systemdesign.parking.model.spot;

import com.systemdesign.parking.model.SpotType;
import com.systemdesign.parking.model.VehicleType;
import com.systemdesign.parking.model.vehicle.Vehicle;

/**
 * Handicapped-accessible parking spot: fits Cars only.
 * Reserved for vehicles with accessibility permits.
 */
public class HandicappedSpot extends ParkingSpot {

    public HandicappedSpot(String spotId, int floorNumber, int spotNumber) {
        super(spotId, floorNumber, spotNumber, SpotType.HANDICAPPED);
    }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getVehicleType() == VehicleType.CAR;
    }
}
