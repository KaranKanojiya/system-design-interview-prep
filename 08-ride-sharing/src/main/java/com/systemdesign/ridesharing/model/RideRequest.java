package com.systemdesign.ridesharing.model;

import java.time.Instant;

/**
 * RideRequest — Immutable DTO representing a rider's request for a ride.
 *
 * WHY separate from Ride:
 *   A RideRequest is what the rider sends. A Ride is the full lifecycle object
 *   that includes the matched driver, actual fare, timestamps, etc.
 *   Keeping them separate means we can queue/process requests independently
 *   of ride state management.
 *
 * In production Uber's architecture:
 *   RideRequest -> MatchingService (finds driver) -> creates Ride object
 *   The request has estimated fare (shown to rider before confirmation),
 *   while the Ride tracks the actual fare.
 */
public class RideRequest {

    private final String riderId;
    private final Location pickup;
    private final Location dropoff;
    private final Vehicle.VehicleType vehicleType;
    private final double estimatedFare;
    private final double surgeMultiplier;
    private final Instant requestTime;

    public RideRequest(String riderId, Location pickup, Location dropoff,
                       Vehicle.VehicleType vehicleType, double estimatedFare,
                       double surgeMultiplier) {
        this.riderId = riderId;
        this.pickup = pickup;
        this.dropoff = dropoff;
        this.vehicleType = vehicleType;
        this.estimatedFare = estimatedFare;
        this.surgeMultiplier = surgeMultiplier;
        this.requestTime = Instant.now();
    }

    public String getRiderId() {
        return riderId;
    }

    public Location getPickup() {
        return pickup;
    }

    public Location getDropoff() {
        return dropoff;
    }

    public Vehicle.VehicleType getVehicleType() {
        return vehicleType;
    }

    public double getEstimatedFare() {
        return estimatedFare;
    }

    public double getSurgeMultiplier() {
        return surgeMultiplier;
    }

    public Instant getRequestTime() {
        return requestTime;
    }

    @Override
    public String toString() {
        return String.format("RideRequest{rider='%s', pickup=%s, dropoff=%s, type=%s, estFare=$%.2f, surge=%.1fx}",
                riderId, pickup, dropoff, vehicleType, estimatedFare, surgeMultiplier);
    }
}
