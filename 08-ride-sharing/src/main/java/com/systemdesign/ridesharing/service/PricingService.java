package com.systemdesign.ridesharing.service;

import com.systemdesign.ridesharing.model.Location;
import com.systemdesign.ridesharing.model.Ride;
import com.systemdesign.ridesharing.model.Vehicle;
import com.systemdesign.ridesharing.strategy.pricing.PricingStrategy;

/**
 * PricingService — Calculates ride fares using the configured PricingStrategy.
 *
 * CALL CHAIN:
 *   Before ride: RideService.requestRide()
 *     -> PricingService.estimateFare(pickup, dropoff, vehicleType, surge)
 *       -> Haversine distance(pickup, dropoff)
 *       -> Estimate duration from distance / average speed
 *       -> PricingStrategy.calculateFare(distance, duration, type, surge)
 *       -> Return estimated fare (shown to rider in app)
 *
 *   After ride: RideService.completeRide()
 *     -> PricingService.calculateActualFare(ride)
 *       -> Use ride.distance (from GPS trace) and actual duration
 *       -> PricingStrategy.calculateFare(distance, duration, type, surge)
 *       -> Return actual fare (charged to rider)
 *
 * WHY two calculations:
 *   The estimate uses Haversine (straight-line) distance and assumes average speed.
 *   The actual fare uses real distance (from GPS trace) and real time elapsed.
 *   If actual < estimate: rider pays actual (they got lucky — short route).
 *   If actual > estimate by a lot: Uber sometimes caps at estimate (rider protection).
 */
public class PricingService {

    /** Average city speed in km/h — used for duration estimation. */
    private static final double AVERAGE_SPEED_KMH = 30.0;

    /** Road detour factor: real roads are ~30% longer than straight-line Haversine. */
    private static final double ROAD_FACTOR = 1.3;

    private final PricingStrategy pricingStrategy;

    public PricingService(PricingStrategy pricingStrategy) {
        this.pricingStrategy = pricingStrategy;
    }

    /**
     * Estimate the fare BEFORE the ride starts.
     *
     * Uses Haversine distance * road factor for distance estimation,
     * and distance / average speed for duration estimation.
     *
     * @param pickup          pickup location
     * @param dropoff         dropoff location
     * @param vehicleType     type of vehicle (affects rate)
     * @param surgeMultiplier current surge multiplier (1.0 = no surge)
     * @return estimated fare in dollars
     */
    public double estimateFare(Location pickup, Location dropoff,
                               Vehicle.VehicleType vehicleType, double surgeMultiplier) {
        // Estimate distance: Haversine (straight-line) * road detour factor
        double straightLineKm = Location.distanceKm(pickup, dropoff);
        double estimatedDistanceKm = straightLineKm * ROAD_FACTOR;

        // Estimate duration: distance / average speed, converted to minutes
        double estimatedDurationMinutes = (estimatedDistanceKm / AVERAGE_SPEED_KMH) * 60.0;

        return pricingStrategy.calculateFare(estimatedDistanceKm, estimatedDurationMinutes,
                vehicleType, surgeMultiplier);
    }

    /**
     * Calculate the ACTUAL fare after the ride completes.
     *
     * Uses the ride's recorded distance and actual time elapsed.
     * In production, distance comes from the GPS trace (sum of point-to-point distances).
     * Here we use the Haversine distance * road factor as a simulation.
     *
     * @param ride the completed ride
     * @return actual fare in dollars
     */
    public double calculateActualFare(Ride ride) {
        // Use the ride's distance (set during creation from Haversine)
        double distanceKm = ride.getDistance() * ROAD_FACTOR;

        // Calculate actual duration if start/end times are available
        double durationMinutes;
        if (ride.getStartTime() != null && ride.getEndTime() != null) {
            long durationMs = ride.getEndTime().toEpochMilli() - ride.getStartTime().toEpochMilli();
            durationMinutes = durationMs / 60000.0;
        } else {
            // Fallback: estimate from distance
            durationMinutes = (distanceKm / AVERAGE_SPEED_KMH) * 60.0;
        }

        // Minimum 1 minute (even very short rides take at least a minute)
        durationMinutes = Math.max(durationMinutes, 1.0);

        Vehicle.VehicleType vehicleType = ride.getDriver() != null
                ? ride.getDriver().getVehicle().getType()
                : Vehicle.VehicleType.SEDAN;

        return pricingStrategy.calculateFare(distanceKm, durationMinutes,
                vehicleType, ride.getSurgeMultiplier());
    }

    /** Get the pricing strategy (for display). */
    public PricingStrategy getPricingStrategy() {
        return pricingStrategy;
    }
}
