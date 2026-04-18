package com.systemdesign.parking.strategy.parking;

import com.systemdesign.parking.model.ParkingLot;
import com.systemdesign.parking.model.spot.ParkingSpot;
import com.systemdesign.parking.model.vehicle.Vehicle;

import java.util.Optional;

/**
 * Strategy interface for parking spot assignment algorithms.
 *
 * Demonstrates:
 * - Strategy pattern: encapsulates interchangeable algorithms
 * - Dependency Inversion: ParkingService depends on this abstraction, not concrete strategies
 * - Open/Closed Principle: new strategies added without modifying existing code
 */
public interface ParkingStrategy {

    /**
     * Find the best available spot for the given vehicle.
     *
     * @param lot     the parking lot to search
     * @param vehicle the vehicle that needs a spot
     * @return an available spot, or empty if none found
     */
    Optional<ParkingSpot> findSpot(ParkingLot lot, Vehicle vehicle);

    /**
     * Human-readable name of this strategy for logging/display.
     */
    String name();
}
