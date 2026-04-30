package com.systemdesign.ridesharing.repository;

import com.systemdesign.ridesharing.model.Driver;
import com.systemdesign.ridesharing.model.Location;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryDriverRepository — ConcurrentHashMap-backed driver storage.
 *
 * NOTE on findNearby():
 *   This implementation does a brute-force scan of all drivers and filters
 *   by Haversine distance. This is O(n) — fine for our demo, but terrible
 *   at scale (1M+ drivers).
 *
 *   In production, findNearby() would delegate to LocationService which uses
 *   a QuadTree or GeoHash index for O(sqrt(n) + k) spatial queries.
 *   The repository here stores driver PROFILE data; spatial queries go
 *   through LocationService.
 */
public class InMemoryDriverRepository implements DriverRepository {

    private final ConcurrentHashMap<String, Driver> drivers = new ConcurrentHashMap<>();

    @Override
    public void save(Driver driver) {
        drivers.put(driver.getId(), driver);
    }

    @Override
    public Optional<Driver> findById(String driverId) {
        return Optional.ofNullable(drivers.get(driverId));
    }

    @Override
    public List<Driver> findAvailable() {
        return drivers.values().stream()
                .filter(Driver::isAvailable)
                .collect(Collectors.toList());
    }

    /**
     * Find available drivers within radiusKm of the given location.
     *
     * NOTE: This is a brute-force O(n) scan. In production, this would use
     * the spatial index (QuadTree/H3). Here it's a fallback for when
     * LocationService isn't wired up.
     */
    @Override
    public List<Driver> findNearby(Location location, double radiusKm) {
        return drivers.values().stream()
                .filter(Driver::isAvailable)
                .filter(d -> Location.distanceKm(d.getCurrentLocation(), location) <= radiusKm)
                .sorted((d1, d2) -> {
                    double dist1 = Location.distanceKm(d1.getCurrentLocation(), location);
                    double dist2 = Location.distanceKm(d2.getCurrentLocation(), location);
                    return Double.compare(dist1, dist2);
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<Driver> findAll() {
        return List.copyOf(drivers.values());
    }

    @Override
    public void delete(String driverId) {
        drivers.remove(driverId);
    }
}
