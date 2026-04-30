package com.systemdesign.ridesharing.repository;

import com.systemdesign.ridesharing.model.Driver;
import com.systemdesign.ridesharing.model.Location;

import java.util.List;
import java.util.Optional;

/**
 * DriverRepository — Data access interface for Driver objects.
 *
 * Key methods:
 *   - findAvailable(): get all drivers not currently on a ride
 *   - findNearby(): spatial query — drivers within N km of a location
 *
 * In production Uber:
 *   Driver data is spread across multiple services:
 *   - Profile service (name, rating, vehicle)
 *   - Location service (current GPS position — real-time, in Redis/H3)
 *   - Availability service (on-trip, idle, offline)
 *   Here we combine them for simplicity.
 */
public interface DriverRepository {

    void save(Driver driver);

    Optional<Driver> findById(String driverId);

    /** Find all available (not busy) drivers. */
    List<Driver> findAvailable();

    /**
     * Find available drivers near a location.
     * This is a convenience method that combines spatial query + availability filter.
     */
    List<Driver> findNearby(Location location, double radiusKm);

    List<Driver> findAll();

    void delete(String driverId);
}
