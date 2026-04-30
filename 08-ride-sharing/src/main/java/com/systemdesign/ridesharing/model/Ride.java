package com.systemdesign.ridesharing.model;

import com.systemdesign.ridesharing.exception.InvalidRideStateException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ride — The core domain object tracking a ride's entire lifecycle.
 *
 * Uses the BUILDER PATTERN for construction (many optional fields)
 * and a STATE MACHINE for transitions (guards prevent invalid moves).
 *
 * UGLY approach (no guards):
 *   ride.setStatus(RideStatus.COMPLETED);  // caller can set ANY status at ANY time
 *   ride.setFare(25.0);                     // who validates this?
 *   // Result: completed rides with no driver, started rides with no pickup time,
 *   // cancelled rides that still get billed. Chaos.
 *
 * CLEAN approach (guarded state machine):
 *   ride.completeRide(25.0);  // throws InvalidRideStateException if status != IN_PROGRESS
 *   // Each transition validates preconditions and sets all related fields atomically.
 *
 * State machine diagram:
 *   REQUESTED ──> MATCHED ──> DRIVER_EN_ROUTE ──> IN_PROGRESS ──> COMPLETED
 *       │             │              │                  │
 *       └─────────────┴──────────────┴──────────────────┘──> CANCELLED
 */
public class Ride {

    private final String rideId;
    private final Rider rider;
    private Driver driver;
    private final Location pickup;
    private final Location dropoff;
    private RideStatus status;
    private final Instant requestTime;
    private Instant matchTime;
    private Instant startTime;
    private Instant endTime;
    private double estimatedFare;
    private double actualFare;
    private double surgeMultiplier;
    private double distance;  // in km
    private List<Location> route;

    // --- Private constructor (use Builder) ---
    private Ride(Builder builder) {
        this.rideId = builder.rideId;
        this.rider = builder.rider;
        this.driver = builder.driver;
        this.pickup = builder.pickup;
        this.dropoff = builder.dropoff;
        this.status = RideStatus.REQUESTED;
        this.requestTime = Instant.now();
        this.estimatedFare = builder.estimatedFare;
        this.surgeMultiplier = builder.surgeMultiplier;
        this.distance = builder.distance;
        this.route = builder.route != null ? builder.route : new ArrayList<>();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  STATE MACHINE TRANSITIONS — each one validates before moving
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Transition: REQUESTED -> MATCHED
     * Guard: status must be REQUESTED
     * Side effects: sets driver, matchTime
     *
     * WHY guard: prevents matching a driver to an already-matched or cancelled ride.
     * In production, this is critical because multiple matching workers might
     * try to assign a driver concurrently (solved with CAS/optimistic locking).
     */
    public void matchDriver(Driver driver) {
        if (status != RideStatus.REQUESTED) {
            throw new InvalidRideStateException(
                    "Cannot match driver — ride is " + status + ", expected REQUESTED");
        }
        this.driver = driver;
        this.matchTime = Instant.now();
        this.status = RideStatus.MATCHED;
    }

    /**
     * Transition: MATCHED -> DRIVER_EN_ROUTE
     * Guard: status must be MATCHED
     * Represents: driver has confirmed and is heading to pickup location.
     */
    public void driverEnRoute() {
        if (status != RideStatus.MATCHED) {
            throw new InvalidRideStateException(
                    "Cannot set en-route — ride is " + status + ", expected MATCHED");
        }
        this.status = RideStatus.DRIVER_EN_ROUTE;
    }

    /**
     * Transition: DRIVER_EN_ROUTE -> IN_PROGRESS
     * Guard: status must be DRIVER_EN_ROUTE
     * Represents: rider has been picked up, ride is underway.
     */
    public void startRide() {
        if (status != RideStatus.DRIVER_EN_ROUTE) {
            throw new InvalidRideStateException(
                    "Cannot start ride — ride is " + status + ", expected DRIVER_EN_ROUTE");
        }
        this.startTime = Instant.now();
        this.status = RideStatus.IN_PROGRESS;
    }

    /**
     * Transition: IN_PROGRESS -> COMPLETED
     * Guard: status must be IN_PROGRESS
     * Side effects: sets actualFare, endTime, updates driver earnings
     *
     * WHY we pass the fare in:
     *   The actual fare is calculated by PricingService based on actual distance/time,
     *   not the estimate. This is where surge pricing gets applied.
     */
    public void completeRide(double fare) {
        if (status != RideStatus.IN_PROGRESS) {
            throw new InvalidRideStateException(
                    "Cannot complete ride — ride is " + status + ", expected IN_PROGRESS");
        }
        this.actualFare = fare;
        this.endTime = Instant.now();
        this.status = RideStatus.COMPLETED;

        // Side effect: update driver stats
        if (driver != null) {
            driver.addEarnings(fare);
            driver.markAvailable();
        }
    }

    /**
     * Transition: (any except COMPLETED) -> CANCELLED
     * Guard: status must NOT be COMPLETED (can't cancel a finished ride)
     * Side effects: sets endTime, releases driver if one was assigned
     *
     * WHY cancellation is allowed from most states:
     *   Rider might cancel while waiting for a match, after match, or even
     *   during ride (emergency). Each scenario has different refund policies
     *   in production (no charge if before match, cancellation fee after, etc.)
     */
    public void cancelRide() {
        if (status == RideStatus.COMPLETED) {
            throw new InvalidRideStateException(
                    "Cannot cancel ride — ride is already COMPLETED");
        }
        if (status == RideStatus.CANCELLED) {
            throw new InvalidRideStateException(
                    "Cannot cancel ride — ride is already CANCELLED");
        }
        this.endTime = Instant.now();
        this.status = RideStatus.CANCELLED;

        // Release the driver if one was assigned
        if (driver != null) {
            driver.markAvailable();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BUILDER — because Ride has many fields, most optional at creation
    // ═══════════════════════════════════════════════════════════════════

    public static class Builder {
        // Required
        private String rideId;
        private Rider rider;
        private Location pickup;
        private Location dropoff;

        // Optional with defaults
        private Driver driver;
        private double estimatedFare;
        private double surgeMultiplier = 1.0;
        private double distance;
        private List<Location> route;

        public Builder(Rider rider, Location pickup, Location dropoff) {
            this.rideId = UUID.randomUUID().toString().substring(0, 8);
            this.rider = rider;
            this.pickup = pickup;
            this.dropoff = dropoff;
        }

        public Builder rideId(String rideId) {
            this.rideId = rideId;
            return this;
        }

        public Builder driver(Driver driver) {
            this.driver = driver;
            return this;
        }

        public Builder estimatedFare(double estimatedFare) {
            this.estimatedFare = estimatedFare;
            return this;
        }

        public Builder surgeMultiplier(double surgeMultiplier) {
            this.surgeMultiplier = surgeMultiplier;
            return this;
        }

        public Builder distance(double distance) {
            this.distance = distance;
            return this;
        }

        public Builder route(List<Location> route) {
            this.route = route;
            return this;
        }

        public Ride build() {
            // Calculate distance from pickup to dropoff if not set
            if (distance == 0.0) {
                distance = Location.distanceKm(pickup, dropoff);
            }
            return new Ride(this);
        }
    }

    // --- Getters ---

    public String getRideId() {
        return rideId;
    }

    public Rider getRider() {
        return rider;
    }

    public Driver getDriver() {
        return driver;
    }

    public Location getPickup() {
        return pickup;
    }

    public Location getDropoff() {
        return dropoff;
    }

    public RideStatus getStatus() {
        return status;
    }

    public Instant getRequestTime() {
        return requestTime;
    }

    public Instant getMatchTime() {
        return matchTime;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public double getEstimatedFare() {
        return estimatedFare;
    }

    public double getActualFare() {
        return actualFare;
    }

    public double getSurgeMultiplier() {
        return surgeMultiplier;
    }

    public double getDistance() {
        return distance;
    }

    public List<Location> getRoute() {
        return route;
    }

    @Override
    public String toString() {
        return String.format("Ride{id='%s', status=%s, rider='%s', driver='%s', " +
                        "pickup=%s, dropoff=%s, dist=%.2fkm, estFare=$%.2f, actualFare=$%.2f, surge=%.1fx}",
                rideId, status,
                rider != null ? rider.getName() : "N/A",
                driver != null ? driver.getName() : "unmatched",
                pickup, dropoff, distance, estimatedFare, actualFare, surgeMultiplier);
    }
}
