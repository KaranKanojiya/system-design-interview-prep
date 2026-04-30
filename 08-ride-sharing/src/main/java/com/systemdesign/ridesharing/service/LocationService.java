package com.systemdesign.ridesharing.service;

import com.systemdesign.ridesharing.model.Driver;
import com.systemdesign.ridesharing.model.Location;
import com.systemdesign.ridesharing.spatial.SpatialIndex;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LocationService — Manages real-time driver locations using a spatial index (QuadTree).
 *
 * CALL CHAIN:
 *   Driver GPS update -> LocationService.updateDriverLocation()
 *       -> SpatialIndex.update(driverId, newLocation)
 *
 *   Ride request -> MatchingService -> LocationService.findNearbyDrivers()
 *       -> SpatialIndex.findNearby(pickup, radius, maxResults)
 *       -> returns list of (driverId, location) pairs sorted by distance
 *
 * WHY a separate LocationService:
 *   In production Uber, the location layer is a separate microservice that:
 *   - Receives 250K+ GPS updates per second (from 1M active drivers)
 *   - Uses an in-memory spatial index (H3/GeoHash + Redis)
 *   - Publishes location events to a stream (Kafka) for other services
 *   - Has its own scaling/caching strategy (Redis Cluster, sharded by region)
 *
 *   Separating it from DriverRepository follows the "single service, single concern"
 *   principle. The DriverRepo manages driver PROFILES; LocationService manages
 *   real-time POSITIONS.
 *
 * THREAD SAFETY:
 *   The SpatialIndex (QuadTree) is not inherently thread-safe. We synchronize
 *   writes (insert/update/remove) to prevent corruption. Reads (findNearby)
 *   are also synchronized to get a consistent snapshot — in production, you'd
 *   use a read-write lock or a concurrent spatial data structure.
 */
public class LocationService {

    private final SpatialIndex spatialIndex;

    public LocationService(SpatialIndex spatialIndex) {
        this.spatialIndex = spatialIndex;
    }

    /**
     * Update a driver's location in the spatial index.
     *
     * Called every ~4 seconds per driver in production. For 1M drivers,
     * that's 250K updates/second — the QuadTree's O(log n) update handles this.
     *
     * @param driverId driver's unique ID
     * @param location new GPS location
     */
    public synchronized void updateDriverLocation(String driverId, Location location) {
        spatialIndex.update(driverId, location);
    }

    /**
     * Insert a driver into the spatial index (first time registration).
     */
    public synchronized void addDriver(String driverId, Location location) {
        spatialIndex.insert(driverId, location);
    }

    /**
     * Find drivers near a pickup location.
     *
     * Algorithm (delegated to QuadTree):
     *   1. Create bounding box from center + radius
     *   2. Range query on QuadTree (prunes distant branches)
     *   3. Filter by Haversine distance (box -> circle correction)
     *   4. Sort by distance, return top maxResults
     *
     * @param location   pickup location
     * @param radiusKm   search radius in kilometers
     * @param maxResults maximum number of drivers to return
     * @return list of (driverId, location) pairs, nearest first
     */
    public synchronized List<Map.Entry<String, Location>> findNearbyDrivers(
            Location location, double radiusKm, int maxResults) {
        return spatialIndex.findNearby(location, radiusKm, maxResults);
    }

    /**
     * Find nearby driver IDs only (convenience method).
     */
    public List<String> findNearbyDriverIds(Location location, double radiusKm, int maxResults) {
        return findNearbyDrivers(location, radiusKm, maxResults).stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Remove a driver from the spatial index (went offline).
     */
    public synchronized void removeDriver(String driverId) {
        spatialIndex.remove(driverId);
    }

    /** Get the underlying spatial index (for debugging/stats). */
    public SpatialIndex getSpatialIndex() {
        return spatialIndex;
    }
}
