package com.systemdesign.ridesharing.spatial;

import com.systemdesign.ridesharing.model.Location;

import java.util.List;
import java.util.Map;

/**
 * SpatialIndex — Interface for spatial data structures that support
 * efficient geographic queries (insert, remove, find nearby).
 *
 * WHY an interface:
 *   Different spatial indexing strategies have different trade-offs:
 *   - QuadTree: good for 2D point data, easy to implement, O(log n) queries
 *   - R-Tree: better for rectangles/polygons, complex but efficient for range queries
 *   - GeoHash: great for distributed systems (string prefix matching), used in Redis
 *   - H3 (Uber's own): hexagonal grid, uniform area coverage, used in production Uber
 *
 *   By coding to this interface, the LocationService can swap implementations
 *   without changing any business logic.
 *
 * In a system design interview:
 *   "We'll use a QuadTree for the spatial index. It gives us O(log n) insertion
 *   and O(sqrt(n) + k) range queries where k is the number of results.
 *   In production, Uber uses H3 (hexagonal hierarchical index) + Redis for
 *   the real-time location layer."
 */
public interface SpatialIndex {

    /**
     * Insert a point (e.g., driver location) into the index.
     * @param id   unique identifier (e.g., driverId)
     * @param loc  geographic location
     */
    void insert(String id, Location loc);

    /**
     * Remove a point from the index.
     * @param id unique identifier
     */
    void remove(String id);

    /**
     * Update a point's location (remove + reinsert).
     * @param id     unique identifier
     * @param newLoc new location
     */
    void update(String id, Location newLoc);

    /**
     * Find points near a center location within a radius.
     * Results are sorted by distance (nearest first).
     *
     * @param center     center of search area
     * @param radiusKm   search radius in kilometers
     * @param maxResults  maximum number of results to return
     * @return list of (id, location) pairs sorted by distance to center
     */
    List<Map.Entry<String, Location>> findNearby(Location center, double radiusKm, int maxResults);

    /** Number of points in the index. */
    int size();

    /** Remove all points. */
    void clear();
}
