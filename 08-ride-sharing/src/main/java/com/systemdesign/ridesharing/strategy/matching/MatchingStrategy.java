package com.systemdesign.ridesharing.strategy.matching;

import com.systemdesign.ridesharing.model.Driver;
import com.systemdesign.ridesharing.model.Location;
import com.systemdesign.ridesharing.model.RideRequest;

import java.util.List;
import java.util.Optional;

/**
 * MatchingStrategy — Strategy pattern for driver-rider matching.
 *
 * WHY Strategy pattern here:
 *   Different matching algorithms are better for different situations:
 *   - NearestDriverStrategy: simplest, picks the closest driver
 *   - ETABasedStrategy: smarter, accounts for traffic/speed (closest != fastest)
 *   - In production, Uber uses ML-based matching that considers:
 *     driver rating, acceptance rate, route optimization, pool compatibility, etc.
 *
 *   By coding to this interface, the MatchingService can swap strategies
 *   at runtime (e.g., use ETA during rush hour, nearest at night).
 *
 * INTERVIEW INSIGHT:
 *   "The matching problem is fundamentally a bipartite matching problem —
 *   we have riders on one side and drivers on the other. The greedy approach
 *   (nearest first) works for individual matches, but a global optimization
 *   (Hungarian algorithm) can reduce total wait time across all riders.
 *   Uber uses a batched matching system that collects requests over 2-second
 *   windows and optimizes globally."
 */
public interface MatchingStrategy {

    /**
     * Find the best driver for a ride request from the list of available drivers.
     *
     * @param request          the ride request (contains pickup, vehicle type preference)
     * @param availableDrivers list of available drivers near the pickup
     * @param pickup           pickup location
     * @return the best matching driver, or empty if no suitable driver found
     */
    Optional<Driver> findBestDriver(RideRequest request, List<Driver> availableDrivers, Location pickup);
}
