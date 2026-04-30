package com.systemdesign.ridesharing.repository;

import com.systemdesign.ridesharing.model.Ride;
import com.systemdesign.ridesharing.model.RideStatus;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryRideRepository — ConcurrentHashMap-backed ride storage.
 *
 * WHY ConcurrentHashMap:
 *   Multiple threads may create/update rides concurrently (e.g., concurrent
 *   ride requests from different riders). ConcurrentHashMap gives us thread-safe
 *   reads and writes without external synchronization.
 *
 * In production:
 *   Rides would be stored in Cassandra/DynamoDB with:
 *   - Primary key: rideId
 *   - Secondary index: riderId (for ride history)
 *   - Secondary index: driverId (for driver's ride history)
 *   - TTL: old rides are archived after 90 days
 */
public class InMemoryRideRepository implements RideRepository {

    private final ConcurrentHashMap<String, Ride> rides = new ConcurrentHashMap<>();

    @Override
    public void save(Ride ride) {
        rides.put(ride.getRideId(), ride);
    }

    @Override
    public Optional<Ride> findById(String rideId) {
        return Optional.ofNullable(rides.get(rideId));
    }

    @Override
    public List<Ride> findByRiderId(String riderId) {
        return rides.values().stream()
                .filter(r -> r.getRider().getId().equals(riderId))
                .collect(Collectors.toList());
    }

    @Override
    public List<Ride> findByDriverId(String driverId) {
        return rides.values().stream()
                .filter(r -> r.getDriver() != null && r.getDriver().getId().equals(driverId))
                .collect(Collectors.toList());
    }

    @Override
    public List<Ride> findByStatus(RideStatus status) {
        return rides.values().stream()
                .filter(r -> r.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public List<Ride> findAll() {
        return List.copyOf(rides.values());
    }

    @Override
    public void delete(String rideId) {
        rides.remove(rideId);
    }
}
