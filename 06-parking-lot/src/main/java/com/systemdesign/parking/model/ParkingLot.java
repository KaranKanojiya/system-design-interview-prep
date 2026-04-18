package com.systemdesign.parking.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Singleton representing the entire parking lot facility.
 *
 * Demonstrates:
 * - Singleton pattern with double-checked locking for thread safety
 * - Composition: ParkingLot has-many ParkingFloors
 * - Encapsulation: internal state only modified through controlled methods
 *
 * Double-checked locking ensures exactly one instance even under concurrent access
 * from multiple entry/exit gate threads.
 */
public class ParkingLot {

    private static volatile ParkingLot instance;

    private final String name;
    private final String address;
    private final List<ParkingFloor> floors;

    private ParkingLot(String name, String address) {
        this.name = name;
        this.address = address;
        this.floors = new ArrayList<>();
    }

    /**
     * Double-checked locking Singleton accessor.
     * The volatile keyword on the instance field prevents instruction reordering
     * that could expose a partially-constructed object to other threads.
     */
    public static ParkingLot getInstance() {
        if (instance == null) {
            throw new IllegalStateException(
                    "ParkingLot not initialized. Call createInstance() first.");
        }
        return instance;
    }

    /**
     * Create the Singleton instance. Called once during application setup.
     */
    public static synchronized ParkingLot createInstance(String name, String address) {
        if (instance == null) {
            instance = new ParkingLot(name, address);
        }
        return instance;
    }

    /**
     * Reset the singleton (for testing / demo re-initialization).
     */
    public static synchronized void resetInstance() {
        instance = null;
    }

    public void addFloor(ParkingFloor floor) {
        floors.add(floor);
    }

    public boolean isFull() {
        return floors.stream().allMatch(ParkingFloor::isFull);
    }

    public int getTotalCapacity() {
        return floors.stream()
                .mapToInt(f -> f.getSpots().size())
                .sum();
    }

    public long getTotalAvailable() {
        return floors.stream()
                .mapToLong(ParkingFloor::getAvailableCount)
                .sum();
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public List<ParkingFloor> getFloors() {
        return Collections.unmodifiableList(floors);
    }

    @Override
    public String toString() {
        return String.format("ParkingLot{name='%s', floors=%d, capacity=%d, available=%d}",
                name, floors.size(), getTotalCapacity(), getTotalAvailable());
    }
}
