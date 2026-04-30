package com.systemdesign.ridesharing.service;

import com.systemdesign.ridesharing.exception.NoDriverAvailableException;
import com.systemdesign.ridesharing.model.Driver;
import com.systemdesign.ridesharing.model.Location;
import com.systemdesign.ridesharing.model.RideRequest;
import com.systemdesign.ridesharing.repository.DriverRepository;
import com.systemdesign.ridesharing.strategy.matching.MatchingStrategy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MatchingService — Finds and matches the best driver for a ride request.
 *
 * CALL CHAIN:
 *   RideService.requestRide()
 *     -> MatchingService.findAndMatchDriver(rideRequest)
 *       -> LocationService.findNearbyDrivers(pickup, radius, maxResults)
 *          [returns list of (driverId, location) pairs from QuadTree]
 *       -> DriverRepository.findById(driverId) for each nearby driver
 *          [hydrates full Driver objects with vehicle type, rating, etc.]
 *       -> MatchingStrategy.findBestDriver(request, drivers, pickup)
 *          [applies strategy: nearest, ETA-based, etc.]
 *       -> Returns matched Driver (or cascades to next if timeout)
 *
 * DRIVER TIMEOUT & CASCADE:
 *   When a driver is matched, they have a limited time (simulated as 5 seconds)
 *   to accept the ride. If they don't accept:
 *   1. Skip that driver and try the next best one
 *   2. Repeat up to MAX_CASCADE_ATTEMPTS times
 *   3. If all attempts fail, throw NoDriverAvailableException
 *
 *   In production Uber:
 *   - Timeout is 15 seconds
 *   - Driver's acceptance rate is tracked (low rate = fewer future offers)
 *   - The system sends the request to the next driver immediately (no wasted time)
 *   - Push notification + sound alert to the driver's phone
 */
public class MatchingService {

    private static final double DEFAULT_SEARCH_RADIUS_KM = 5.0;
    private static final int DEFAULT_MAX_NEARBY = 10;

    /** Maximum cascade attempts — try this many drivers before giving up. */
    private static final int MAX_CASCADE_ATTEMPTS = 3;

    private final LocationService locationService;
    private final DriverRepository driverRepository;
    private final MatchingStrategy matchingStrategy;

    public MatchingService(LocationService locationService,
                           DriverRepository driverRepository,
                           MatchingStrategy matchingStrategy) {
        this.locationService = locationService;
        this.driverRepository = driverRepository;
        this.matchingStrategy = matchingStrategy;
    }

    /**
     * Find and match the best driver for a ride request.
     *
     * Algorithm:
     *   1. Use LocationService (QuadTree) to find nearby drivers
     *   2. Hydrate full Driver objects from DriverRepository
     *   3. Filter: available + correct vehicle type
     *   4. Apply MatchingStrategy to pick the best one
     *   5. If first choice times out, cascade to next
     *
     * @param request the ride request
     * @return matched driver
     * @throws NoDriverAvailableException if no driver can be found
     */
    public Driver findAndMatchDriver(RideRequest request) {
        Location pickup = request.getPickup();

        // Step 1: Find nearby drivers using spatial index (O(sqrt(n) + k) via QuadTree)
        List<Map.Entry<String, Location>> nearbyEntries =
                locationService.findNearbyDrivers(pickup, DEFAULT_SEARCH_RADIUS_KM, DEFAULT_MAX_NEARBY);

        if (nearbyEntries.isEmpty()) {
            throw new NoDriverAvailableException(request.getRiderId(),
                    "No drivers within " + DEFAULT_SEARCH_RADIUS_KM + " km of pickup " + pickup);
        }

        // Step 2: Hydrate full Driver objects from repository
        // WHY we hydrate: the spatial index only stores (id, location).
        // We need the full Driver object (vehicle type, rating, availability) for matching.
        List<Driver> nearbyDrivers = nearbyEntries.stream()
                .map(entry -> driverRepository.findById(entry.getKey()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(Driver::isAvailable)
                .collect(Collectors.toList());

        if (nearbyDrivers.isEmpty()) {
            throw new NoDriverAvailableException(request.getRiderId(),
                    "All nearby drivers are busy");
        }

        // Step 3 & 4: Apply matching strategy with cascade on timeout
        return findDriverWithCascade(request, nearbyDrivers, pickup);
    }

    /**
     * Try to match a driver, cascading to the next one on timeout.
     *
     * DRIVER TIMEOUT SIMULATION:
     *   In production, a push notification is sent to the driver's phone.
     *   They have 15 seconds to tap "Accept". If they miss it, the request
     *   cascades to the next best driver.
     *
     *   Here we simulate timeout: the first driver in the list has a
     *   configurable chance of "not accepting" (to demo the cascade).
     *   For the demo, we use a simple simulation flag.
     */
    private Driver findDriverWithCascade(RideRequest request, List<Driver> drivers, Location pickup) {
        for (int attempt = 0; attempt < MAX_CASCADE_ATTEMPTS && !drivers.isEmpty(); attempt++) {
            Optional<Driver> bestDriver = matchingStrategy.findBestDriver(request, drivers, pickup);

            if (bestDriver.isPresent()) {
                Driver driver = bestDriver.get();

                // Simulate driver acceptance (in production: push notification + wait)
                boolean accepted = simulateDriverAcceptance(driver, attempt);

                if (accepted) {
                    System.out.printf("  [Matching] Driver '%s' accepted ride (attempt %d)%n",
                            driver.getName(), attempt + 1);
                    return driver;
                } else {
                    System.out.printf("  [Matching] Driver '%s' timed out (attempt %d/%d), cascading...%n",
                            driver.getName(), attempt + 1, MAX_CASCADE_ATTEMPTS);
                    // Remove this driver from candidates and try next
                    drivers.remove(driver);
                }
            } else {
                break;
            }
        }

        throw new NoDriverAvailableException(request.getRiderId(),
                "All matched drivers timed out after " + MAX_CASCADE_ATTEMPTS + " attempts");
    }

    /**
     * Simulate whether a driver accepts the ride.
     *
     * For demo purposes:
     *   - First attempt: 70% acceptance rate (some drivers are busy/AFK)
     *   - Subsequent attempts: 90% acceptance rate (cascade targets more responsive drivers)
     *
     * In production, this would be replaced by actual push notification + timer.
     */
    private boolean simulateDriverAcceptance(Driver driver, int attempt) {
        // Use driver ID hash for deterministic behavior in demos
        // This ensures the same driver always behaves the same way
        int hash = Math.abs(driver.getId().hashCode());
        if (attempt == 0) {
            return (hash % 10) >= 3;  // 70% acceptance on first attempt
        }
        return (hash % 10) >= 1;  // 90% acceptance on cascade
    }

    /** Get the matching strategy (for display/comparison). */
    public MatchingStrategy getMatchingStrategy() {
        return matchingStrategy;
    }

    /**
     * Convenience: find and match with explicit cascade simulation control.
     * Used in demo to force timeout on first driver.
     */
    public Optional<Driver> findDriverWithForcedTimeout(RideRequest request, List<Driver> drivers,
                                                         Location pickup, int driverToTimeoutIndex) {
        if (drivers.isEmpty()) return Optional.empty();

        for (int attempt = 0; attempt < MAX_CASCADE_ATTEMPTS && !drivers.isEmpty(); attempt++) {
            Optional<Driver> bestDriver = matchingStrategy.findBestDriver(request, drivers, pickup);

            if (bestDriver.isPresent()) {
                Driver driver = bestDriver.get();

                // Force timeout for the specified driver index
                if (attempt < driverToTimeoutIndex) {
                    System.out.printf("  [Matching] Driver '%s' TIMED OUT (simulated, attempt %d/%d)%n",
                            driver.getName(), attempt + 1, MAX_CASCADE_ATTEMPTS);
                    drivers.remove(driver);
                    continue;
                }

                System.out.printf("  [Matching] Driver '%s' ACCEPTED ride (attempt %d)%n",
                        driver.getName(), attempt + 1);
                return Optional.of(driver);
            } else {
                break;
            }
        }
        return Optional.empty();
    }
}
