package com.systemdesign.ridesharing.repository;

import com.systemdesign.ridesharing.model.Ride;
import com.systemdesign.ridesharing.model.RideStatus;

import java.util.List;
import java.util.Optional;

/**
 * RideRepository — Data access interface for Ride objects.
 *
 * WHY an interface:
 *   In production, rides are stored in a database (Cassandra for Uber, with
 *   different tables optimized for different query patterns).
 *   Here we use InMemoryRideRepository (ConcurrentHashMap) for simplicity.
 *   The service layer doesn't know or care about the storage mechanism.
 */
public interface RideRepository {

    void save(Ride ride);

    Optional<Ride> findById(String rideId);

    List<Ride> findByRiderId(String riderId);

    List<Ride> findByDriverId(String driverId);

    List<Ride> findByStatus(RideStatus status);

    List<Ride> findAll();

    void delete(String rideId);
}
