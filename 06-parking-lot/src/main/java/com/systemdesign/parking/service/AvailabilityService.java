package com.systemdesign.parking.service;

import com.systemdesign.parking.model.ParkingFloor;
import com.systemdesign.parking.model.ParkingLot;
import com.systemdesign.parking.model.SpotType;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service for querying parking availability by floor and spot type.
 *
 * Demonstrates Single Responsibility: separated from ParkingService
 * because availability queries are a distinct concern from park/unpark operations.
 */
public class AvailabilityService {

    private final ParkingLot lot;

    public AvailabilityService(ParkingLot lot) {
        this.lot = lot;
    }

    /**
     * Get availability breakdown: floor -> spotType -> [available, total].
     */
    public Map<Integer, Map<SpotType, int[]>> getAvailability() {
        Map<Integer, Map<SpotType, int[]>> result = new LinkedHashMap<>();

        for (ParkingFloor floor : lot.getFloors()) {
            Map<SpotType, int[]> floorData = new EnumMap<>(SpotType.class);
            for (SpotType type : SpotType.values()) {
                long available = floor.getAvailableCountByType(type);
                long total = floor.getTotalCountByType(type);
                if (total > 0) {
                    floorData.put(type, new int[]{(int) available, (int) total});
                }
            }
            result.put(floor.getFloorNumber(), floorData);
        }

        return result;
    }

    /**
     * Get a formatted availability summary string.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Availability Summary ===\n");

        Map<Integer, Map<SpotType, int[]>> availability = getAvailability();
        for (var entry : availability.entrySet()) {
            sb.append(String.format("Floor %d: ", entry.getKey()));
            var floorData = entry.getValue();
            for (var spotEntry : floorData.entrySet()) {
                int[] counts = spotEntry.getValue();
                sb.append(String.format("%s %d/%d  ",
                        spotEntry.getKey().getDisplayName(), counts[0], counts[1]));
            }
            sb.append("\n");
        }

        sb.append(String.format("Total: %d/%d available%n",
                lot.getTotalAvailable(), lot.getTotalCapacity()));
        return sb.toString();
    }
}
