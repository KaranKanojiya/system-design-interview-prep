package com.systemdesign.ridesharing.spatial;

import com.systemdesign.ridesharing.model.Location;

/**
 * BoundingBox — Axis-aligned rectangle in geographic coordinates.
 *
 * Used by the QuadTree to define the spatial extent of each node.
 * Every QuadTree node owns a bounding box; when we subdivide, we split
 * the box into 4 equal quadrants (NW, NE, SW, SE).
 *
 * WHY axis-aligned (lat/lng aligned):
 *   Simplicity. A rotated bounding box would be more accurate for diagonal
 *   streets, but lat/lng-aligned boxes are trivial to split and intersect.
 *   For the distances we care about (a few km in a city), the distortion
 *   from treating lat/lng as a flat grid is acceptable.
 *
 * APPROXIMATION in fromCenter():
 *   1 degree of latitude  ~ 111 km (constant everywhere)
 *   1 degree of longitude ~ 111 * cos(latitude) km (shrinks toward poles)
 *   We use these approximations to convert a radius in km to a bounding box
 *   in degrees. It's accurate enough for nearby searches within a city.
 */
public class BoundingBox {

    private final double minLat;
    private final double maxLat;
    private final double minLng;
    private final double maxLng;

    public BoundingBox(double minLat, double maxLat, double minLng, double maxLng) {
        this.minLat = minLat;
        this.maxLat = maxLat;
        this.minLng = minLng;
        this.maxLng = maxLng;
    }

    /**
     * Check if a location falls within this bounding box.
     * Inclusive on all edges.
     */
    public boolean contains(Location loc) {
        return loc.getLat() >= minLat && loc.getLat() <= maxLat
            && loc.getLng() >= minLng && loc.getLng() <= maxLng;
    }

    /**
     * Check if this bounding box intersects another.
     * Two boxes intersect if they overlap on BOTH axes.
     *
     * Visualization:
     *   Box A:  [minLatA..maxLatA] x [minLngA..maxLngA]
     *   Box B:  [minLatB..maxLatB] x [minLngB..maxLngB]
     *   They DON'T intersect if:
     *     A is entirely above B:  minLatA > maxLatB
     *     A is entirely below B:  maxLatA < minLatB
     *     A is entirely right of B: minLngA > maxLngB
     *     A is entirely left of B:  maxLngA < minLngB
     *   They intersect if none of the above are true.
     */
    public boolean intersects(BoundingBox other) {
        return !(this.minLat > other.maxLat || this.maxLat < other.minLat
              || this.minLng > other.maxLng || this.maxLng < other.minLng);
    }

    /**
     * Create a bounding box centered on a location with a given radius.
     *
     * Converts km to approximate degrees:
     *   Latitude:  1 km ~ 1/111 degrees
     *   Longitude: 1 km ~ 1/(111 * cos(lat)) degrees
     *
     * WHY this is an approximation:
     *   Earth is not a perfect sphere, and longitude degrees shrink toward
     *   the poles. For a 5-10 km radius in a city, the error is < 1%.
     */
    public static BoundingBox fromCenter(Location center, double radiusKm) {
        // 1 degree of latitude = ~111 km
        double latDelta = radiusKm / 111.0;

        // 1 degree of longitude = ~111 * cos(lat) km
        // At the equator: 111 km. At 60N: ~55.5 km.
        double lngDelta = radiusKm / (111.0 * Math.cos(Math.toRadians(center.getLat())));

        return new BoundingBox(
                center.getLat() - latDelta,
                center.getLat() + latDelta,
                center.getLng() - lngDelta,
                center.getLng() + lngDelta
        );
    }

    // --- Getters ---

    public double getMinLat() {
        return minLat;
    }

    public double getMaxLat() {
        return maxLat;
    }

    public double getMinLng() {
        return minLng;
    }

    public double getMaxLng() {
        return maxLng;
    }

    /** Center latitude of this box. */
    public double getCenterLat() {
        return (minLat + maxLat) / 2.0;
    }

    /** Center longitude of this box. */
    public double getCenterLng() {
        return (minLng + maxLng) / 2.0;
    }

    @Override
    public String toString() {
        return String.format("BoundingBox[lat: %.6f..%.6f, lng: %.6f..%.6f]",
                minLat, maxLat, minLng, maxLng);
    }
}
