package com.systemdesign.parking.model;

import com.systemdesign.parking.model.spot.ParkingSpot;
import com.systemdesign.parking.model.vehicle.Vehicle;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Represents a single floor in the parking structure.
 * Aggregates spots and provides floor-level queries for availability.
 *
 * Uses CopyOnWriteArrayList for thread-safe iteration during concurrent
 * park/vacate operations across multiple entry/exit gates.
 */
public class ParkingFloor {

    private final int floorNumber;
    private final List<ParkingSpot> spots;
    private final Map<SpotType, List<ParkingSpot>> spotsByType;

    public ParkingFloor(int floorNumber) {
        this.floorNumber = floorNumber;
        this.spots = new CopyOnWriteArrayList<>();
        this.spotsByType = new EnumMap<>(SpotType.class);
        for (SpotType type : SpotType.values()) {
            spotsByType.put(type, new CopyOnWriteArrayList<>());
        }
    }

    public void addSpot(ParkingSpot spot) {
        spots.add(spot);
        spotsByType.get(spot.getSpotType()).add(spot);
    }

    /**
     * Find all available spots on this floor that can fit the given vehicle.
     * Leverages polymorphic canFitVehicle() -- each spot subclass decides compatibility.
     */
    public List<ParkingSpot> getAvailableSpots(Vehicle vehicle) {
        return spots.stream()
                .filter(spot -> spot.isAvailable() && spot.canFitVehicle(vehicle))
                .collect(Collectors.toList());
    }

    public long getAvailableCount() {
        return spots.stream().filter(ParkingSpot::isAvailable).count();
    }

    public long getAvailableCountByType(SpotType type) {
        return spotsByType.getOrDefault(type, Collections.emptyList())
                .stream()
                .filter(ParkingSpot::isAvailable)
                .count();
    }

    public long getTotalCountByType(SpotType type) {
        return spotsByType.getOrDefault(type, Collections.emptyList()).size();
    }

    public boolean isFull() {
        return getAvailableCount() == 0;
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    public List<ParkingSpot> getSpots() {
        return Collections.unmodifiableList(spots);
    }

    public Map<SpotType, List<ParkingSpot>> getSpotsByType() {
        return Collections.unmodifiableMap(spotsByType);
    }

    public String getDisplaySummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Floor %d: ", floorNumber));
        List<String> parts = new ArrayList<>();
        for (SpotType type : SpotType.values()) {
            long total = getTotalCountByType(type);
            if (total > 0) {
                long available = getAvailableCountByType(type);
                parts.add(String.format("%s %d/%d", type.getDisplayName(), available, total));
            }
        }
        sb.append(String.join(" | ", parts));
        return sb.toString();
    }

    @Override
    public String toString() {
        return getDisplaySummary();
    }
}
