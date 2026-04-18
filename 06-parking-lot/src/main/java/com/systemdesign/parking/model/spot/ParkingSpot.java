package com.systemdesign.parking.model.spot;

import com.systemdesign.parking.model.SpotStatus;
import com.systemdesign.parking.model.SpotType;
import com.systemdesign.parking.model.vehicle.Vehicle;

import java.util.Objects;

/**
 * Abstract base class for all parking spot types.
 *
 * Demonstrates:
 * - Template Method pattern: canFitVehicle() is abstract, park()/vacate() use it
 * - Open/Closed Principle: new spot types extend this class
 * - Liskov Substitution: any ParkingSpot subclass works wherever ParkingSpot is expected
 * - Thread safety: park() and vacate() are synchronized to prevent race conditions
 *   when multiple entry gates try to assign the same spot concurrently
 */
public abstract class ParkingSpot {

    private final String spotId;
    private final int floorNumber;
    private final int spotNumber;
    private final SpotType spotType;
    private volatile SpotStatus status;
    private Vehicle currentVehicle;

    protected ParkingSpot(String spotId, int floorNumber, int spotNumber, SpotType spotType) {
        this.spotId = Objects.requireNonNull(spotId, "Spot ID cannot be null");
        this.floorNumber = floorNumber;
        this.spotNumber = spotNumber;
        this.spotType = Objects.requireNonNull(spotType, "Spot type cannot be null");
        this.status = SpotStatus.AVAILABLE;
    }

    /**
     * Template method: subclasses define which vehicle types fit in this spot.
     * This is the core polymorphic behavior of the spot hierarchy.
     */
    public abstract boolean canFitVehicle(Vehicle vehicle);

    /**
     * Attempt to park a vehicle in this spot. Thread-safe via synchronization.
     *
     * @param vehicle the vehicle to park
     * @return true if successfully parked, false if spot unavailable or vehicle doesn't fit
     */
    public synchronized boolean park(Vehicle vehicle) {
        if (!isAvailable() || !canFitVehicle(vehicle)) {
            return false;
        }
        this.status = SpotStatus.OCCUPIED;
        this.currentVehicle = vehicle;
        return true;
    }

    /**
     * Vacate this spot, making it available again. Thread-safe via synchronization.
     *
     * @return true if successfully vacated, false if spot was not occupied
     */
    public synchronized boolean vacate() {
        if (status != SpotStatus.OCCUPIED) {
            return false;
        }
        this.status = SpotStatus.AVAILABLE;
        this.currentVehicle = null;
        return true;
    }

    public boolean isAvailable() {
        return status == SpotStatus.AVAILABLE;
    }

    // --- Getters ---

    public String getSpotId() {
        return spotId;
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    public int getSpotNumber() {
        return spotNumber;
    }

    public SpotType getSpotType() {
        return spotType;
    }

    public SpotStatus getStatus() {
        return status;
    }

    public Vehicle getCurrentVehicle() {
        return currentVehicle;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParkingSpot that = (ParkingSpot) o;
        return spotId.equals(that.spotId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(spotId);
    }

    @Override
    public String toString() {
        String base = String.format("F%d-S%03d [%s] %s",
                floorNumber, spotNumber, spotType.getDisplayName(), status.getSymbol());
        if (currentVehicle != null) {
            base += " " + currentVehicle.getLicensePlate();
        }
        return base;
    }
}
