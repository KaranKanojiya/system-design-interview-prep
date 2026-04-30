package com.systemdesign.ridesharing.model;

/**
 * Driver — A person offering rides.
 *
 * Key design decisions:
 *   - isAvailable flag: prevents assigning the same driver to two riders
 *   - markBusy/markAvailable: explicit state transitions (not raw setter)
 *     because in production these trigger events (location tracking frequency
 *     changes, surge recalculation, etc.)
 *   - totalRides/earnings: denormalized counters for quick access
 *     (in production, computed from ride history service)
 *
 * Thread safety note:
 *   In production, driver state updates are protected by optimistic locking
 *   (version field + CAS). Here we keep it simple — the service layer handles
 *   synchronization where needed.
 */
public class Driver {

    private final String id;
    private final String name;
    private double rating;
    private final Vehicle vehicle;
    private volatile boolean isAvailable;   // volatile for visibility across threads
    private Location currentLocation;
    private int totalRides;
    private double earnings;

    public Driver(String id, String name, double rating, Vehicle vehicle, Location currentLocation) {
        this.id = id;
        this.name = name;
        this.rating = rating;
        this.vehicle = vehicle;
        this.isAvailable = true;  // drivers start as available
        this.currentLocation = currentLocation;
        this.totalRides = 0;
        this.earnings = 0.0;
    }

    // --- State transitions ---

    /**
     * Mark driver as busy (matched to a ride).
     * WHY a method instead of setAvailable(false):
     *   In production, this would also: stop sending ride requests to this driver,
     *   increase location update frequency (every 4s instead of 30s for routing),
     *   and emit an event for analytics.
     */
    public void markBusy() {
        this.isAvailable = false;
    }

    /** Mark driver as available again (ride completed/cancelled). */
    public void markAvailable() {
        this.isAvailable = true;
    }

    /** Update driver's GPS location (called every few seconds in production). */
    public void updateLocation(Location newLocation) {
        this.currentLocation = newLocation;
    }

    /** Record a completed ride's earnings. */
    public void addEarnings(double fare) {
        this.totalRides++;
        this.earnings += fare;
    }

    // --- Getters ---

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public double getRating() {
        return rating;
    }

    public void setRating(double rating) {
        this.rating = rating;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public boolean isAvailable() {
        return isAvailable;
    }

    public Location getCurrentLocation() {
        return currentLocation;
    }

    public int getTotalRides() {
        return totalRides;
    }

    public double getEarnings() {
        return earnings;
    }

    @Override
    public String toString() {
        return String.format("Driver{id='%s', name='%s', rating=%.1f, vehicle=%s, available=%s, location=%s, rides=%d, earnings=$%.2f}",
                id, name, rating, vehicle, isAvailable, currentLocation, totalRides, earnings);
    }
}
