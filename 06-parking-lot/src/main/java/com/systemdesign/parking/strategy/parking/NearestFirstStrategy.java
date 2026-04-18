package com.systemdesign.parking.strategy.parking;

import com.systemdesign.parking.model.ParkingFloor;
import com.systemdesign.parking.model.ParkingLot;
import com.systemdesign.parking.model.spot.ParkingSpot;
import com.systemdesign.parking.model.vehicle.Vehicle;

import java.util.List;
import java.util.Optional;

/**
 * Nearest First Strategy: assigns the first available spot starting from Floor 1.
 *
 * Scans floors sequentially (1 -> N) and returns the first compatible spot found.
 * Optimizes for driver convenience (closest to entrance).
 */
public class NearestFirstStrategy implements ParkingStrategy {

    @Override
    public Optional<ParkingSpot> findSpot(ParkingLot lot, Vehicle vehicle) {
        for (ParkingFloor floor : lot.getFloors()) {
            System.out.printf("  [STRATEGY] Nearest First: Checking floor %d...%n", floor.getFloorNumber());
            List<ParkingSpot> available = floor.getAvailableSpots(vehicle);
            if (!available.isEmpty()) {
                ParkingSpot spot = available.getFirst();
                System.out.printf("  [STRATEGY] Nearest First: Found %s on floor %d%n",
                        spot.getSpotId(), floor.getFloorNumber());
                return Optional.of(spot);
            }
        }
        System.out.println("  [STRATEGY] Nearest First: No spot available for " + vehicle);
        return Optional.empty();
    }

    @Override
    public String name() {
        return "Nearest First";
    }
}
