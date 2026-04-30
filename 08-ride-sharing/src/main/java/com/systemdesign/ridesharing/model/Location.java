package com.systemdesign.ridesharing.model;

/**
 * Location — Core value object representing a geographic coordinate (lat/lng).
 *
 * WHY this class matters in a ride-sharing system:
 * -------------------------------------------------
 * Every operation in Uber/Lyft revolves around location:
 *   Rider pickup/dropoff, driver position, surge zone centers, spatial queries.
 *   The Haversine formula is the backbone — it computes great-circle distance
 *   between two points on Earth, accounting for the planet's curvature.
 *
 * UGLY approach (seen in interviews):
 *   double dist = Math.sqrt(Math.pow(lat2-lat1, 2) + Math.pow(lng2-lng1, 2));
 *   // WRONG — treats lat/lng as flat Cartesian. At the equator, 1 degree of
 *   // longitude ~ 111 km, but at 60N latitude it's only ~ 55 km. This gives
 *   // wildly inaccurate results for any real-world usage.
 *
 * CLEAN approach:
 *   Haversine formula — correct for distances up to ~1000 km.
 *   For very long distances, Vincenty's formula is more accurate but overkill here.
 */
public class Location {

    private final double lat;  // latitude in degrees  (-90 to 90)
    private final double lng;  // longitude in degrees (-180 to 180)

    /** Earth's mean radius in kilometers — used in Haversine formula. */
    private static final double EARTH_RADIUS_KM = 6371.0;

    public Location(double lat, double lng) {
        // Guard: validate coordinate ranges
        if (lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90, got: " + lat);
        }
        if (lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180, got: " + lng);
        }
        this.lat = lat;
        this.lng = lng;
    }

    /**
     * Haversine formula — computes great-circle distance between two points on Earth.
     *
     * The math:
     *   a = sin^2(delta_lat / 2) + cos(lat1) * cos(lat2) * sin^2(delta_lng / 2)
     *   c = 2 * atan2(sqrt(a), sqrt(1 - a))
     *   d = R * c
     *
     * WHERE:
     *   - delta_lat, delta_lng are the differences in radians
     *   - R is Earth's radius (6371 km)
     *   - The result 'd' is the shortest distance over the Earth's surface
     *
     * WHY atan2 instead of asin:
     *   atan2(sqrt(a), sqrt(1-a)) is numerically more stable for small distances
     *   than the equivalent 2*asin(sqrt(a)). For very small 'a', sqrt(1-a) ~ 1,
     *   so atan2 avoids floating-point precision issues.
     *
     * @param from starting location
     * @param to   ending location
     * @return distance in kilometers
     */
    public static double distanceKm(Location from, Location to) {
        // Step 1: Convert degrees to radians
        double lat1Rad = Math.toRadians(from.lat);
        double lat2Rad = Math.toRadians(to.lat);
        double deltaLatRad = Math.toRadians(to.lat - from.lat);
        double deltaLngRad = Math.toRadians(to.lng - from.lng);

        // Step 2: Haversine of the central angle
        //   sin^2(delta_lat/2) handles the latitude component
        //   cos(lat1)*cos(lat2)*sin^2(delta_lng/2) handles the longitude component
        //   (longitude degrees shrink as you move toward the poles — the cos terms account for this)
        double a = Math.sin(deltaLatRad / 2) * Math.sin(deltaLatRad / 2)
                 + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                 * Math.sin(deltaLngRad / 2) * Math.sin(deltaLngRad / 2);

        // Step 3: Angular distance in radians
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        // Step 4: Distance = radius * angular distance
        return EARTH_RADIUS_KM * c;
    }

    /**
     * Convenience: distance from this location to another.
     */
    public double distanceTo(Location other) {
        return distanceKm(this, other);
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    @Override
    public String toString() {
        return String.format("(%,.6f, %,.6f)", lat, lng);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Location location = (Location) o;
        return Double.compare(location.lat, lat) == 0
            && Double.compare(location.lng, lng) == 0;
    }

    @Override
    public int hashCode() {
        long latBits = Double.doubleToLongBits(lat);
        long lngBits = Double.doubleToLongBits(lng);
        return 31 * Long.hashCode(latBits) + Long.hashCode(lngBits);
    }
}
