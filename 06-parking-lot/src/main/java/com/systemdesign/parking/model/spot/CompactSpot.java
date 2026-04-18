package com.systemdesign.parking.model.spot;

import com.systemdesign.parking.model.SpotType;
import com.systemdesign.parking.model.VehicleType;
import com.systemdesign.parking.model.vehicle.Vehicle;

/**
 * Compact parking spot: fits Cars and Motorcycles.
 * Motorcycles can use compact spots when motorcycle-only spots are full.
 */
public class CompactSpot extends ParkingSpot {

    public CompactSpot(String spotId, int floorNumber, int spotNumber) {
        super(spotId, floorNumber, spotNumber, SpotType.COMPACT);
    }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        VehicleType type = vehicle.getVehicleType();
        return type == VehicleType.CAR || type == VehicleType.MOTORCYCLE;
    }
}
