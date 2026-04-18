package com.systemdesign.parking.strategy.parking;

import com.systemdesign.parking.model.ParkingFloor;
import com.systemdesign.parking.model.ParkingLot;
import com.systemdesign.parking.model.spot.ParkingSpot;
import com.systemdesign.parking.model.vehicle.Vehicle;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Compact Floor Strategy: fills each floor completely before moving to the next.
 *
 * Selects the floor with the fewest available spots (i.e., the most-full floor)
 * that still has room for the vehicle. This concentrates vehicles and keeps
 * other floors entirely free for large groups or events.
 */
public class CompactFloorStrategy implements ParkingStrategy {

    @Override
    public Optional<ParkingSpot> findSpot(ParkingLot lot, Vehicle vehicle) {
        // Find the most-full floor that still has a compatible spot
        Optional<ParkingFloor> targetFloor = lot.getFloors().stream()
                .filter(floor -> !floor.getAvailableSpots(vehicle).isEmpty())
                .min(Comparator.comparingLong(ParkingFloor::getAvailableCount));

        if (targetFloor.isPresent()) {
            ParkingFloor floor = targetFloor.get();
            List<ParkingSpot> available = floor.getAvailableSpots(vehicle);
            ParkingSpot spot = available.getFirst();
            System.out.printf("  [STRATEGY] Compact Floor: Selected floor %d (fewest available: %d) -> %s%n",
                    floor.getFloorNumber(), floor.getAvailableCount(), spot.getSpotId());
            return Optional.of(spot);
        }

        System.out.println("  [STRATEGY] Compact Floor: No spot available for " + vehicle);
        return Optional.empty();
    }

    @Override
    public String name() {
        return "Compact Floor";
    }
}
