package com.systemdesign.parking.repository;

import com.systemdesign.parking.model.SpotStatus;
import com.systemdesign.parking.model.SpotType;
import com.systemdesign.parking.model.spot.ParkingSpot;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for parking spot persistence.
 *
 * Demonstrates:
 * - Repository pattern: abstracts data access from business logic
 * - Dependency Inversion: services depend on this interface, not implementations
 * - Interface Segregation: only spot-related operations
 */
public interface SpotRepository {

    List<ParkingSpot> findAvailableByType(SpotType type, int floor);

    Optional<ParkingSpot> findById(String spotId);

    void updateStatus(String spotId, SpotStatus status);
}
