package com.systemdesign.ridesharing.spatial;

import com.systemdesign.ridesharing.model.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * GeoHash — Encodes lat/lng coordinates into a compact string.
 *
 * HOW GEOHASH WORKS (bit-interleaving algorithm):
 *
 * Step 1: Binary subdivision of latitude and longitude
 *   Take the latitude range [-90, 90] and longitude range [-180, 180].
 *   For each bit of precision:
 *   - If the coordinate is in the upper half of the current range, output 1
 *   - If the coordinate is in the lower half, output 0
 *   - Narrow the range to the chosen half
 *   This produces two binary strings: one for latitude, one for longitude.
 *
 * Step 2: Interleave the bits
 *   Merge the longitude and latitude bits alternately:
 *   longitude bits: L0 L1 L2 L3 ...
 *   latitude bits:  A0 A1 A2 A3 ...
 *   interleaved:    L0 A0 L1 A1 L2 A2 L3 A3 ...
 *   (longitude goes first by convention)
 *
 * Step 3: Base32 encode
 *   Group the interleaved bits into 5-bit chunks.
 *   Map each chunk to a character using base32 alphabet:
 *   "0123456789bcdefghjkmnpqrstuvwxyz"
 *   (note: a, i, l, o are excluded to avoid confusion with 0, 1)
 *
 * WHY GEOHASH IS USEFUL:
 *   - Nearby points share a common prefix (spatial locality)
 *   - "9q8yy" and "9q8yz" are adjacent cells
 *   - You can find nearby points by querying for the same prefix in a database
 *   - Works great with Redis GEOADD / GEOSEARCH (internally uses geohash)
 *   - String comparison gives approximate distance
 *
 * PRECISION vs AREA:
 *   Precision 1: ~5000 km x 5000 km
 *   Precision 3: ~156 km x 156 km
 *   Precision 5: ~5 km x 5 km     (neighborhood level)
 *   Precision 6: ~1.2 km x 0.6 km (street level)
 *   Precision 8: ~40 m x 19 m     (building level)
 *   Precision 12: ~4 cm x 2 cm    (absurd precision)
 *
 * LIMITATION (edge case):
 *   Points near a cell boundary may be close in distance but have completely
 *   different geohashes. Solution: also query neighboring cells.
 *   That's what getNeighbors() is for.
 *
 * INTERVIEW TIP:
 *   "GeoHash is used in distributed systems because it maps 2D coordinates
 *   to a 1D string that preserves locality. You can shard drivers by geohash
 *   prefix — all drivers in 9q8y* go to the same shard. For range queries,
 *   query the target cell + its 8 neighbors to handle boundary cases."
 */
public class GeoHash {

    /**
     * Base32 alphabet used by GeoHash (32 characters).
     * Excludes: a, i, l, o (look like 0, 1 in some fonts).
     */
    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private GeoHash() {} // utility class — no instances

    /**
     * Encode a lat/lng pair into a geohash string of the given precision.
     *
     * Algorithm walkthrough for encode(37.7749, -122.4194, 5):
     *   We need 5 * 5 = 25 bits total (5 bits per base32 character).
     *   Longitude gets 13 bits, latitude gets 12 bits (longitude goes first).
     *
     *   Bit 0 (lng): range [-180, 180], mid=0. -122.4194 < 0 -> bit=0, range=[-180, 0]
     *   Bit 1 (lat): range [-90, 90], mid=0. 37.7749 >= 0 -> bit=1, range=[0, 90]
     *   Bit 2 (lng): range [-180, 0], mid=-90. -122.4194 < -90 -> bit=0, range=[-180, -90]
     *   ... continues for 25 bits total.
     *
     *   Every 5 bits are converted to a base32 character.
     *
     * @param lat       latitude (-90 to 90)
     * @param lng       longitude (-180 to 180)
     * @param precision number of characters in output (1-12)
     * @return geohash string
     */
    public static String encode(double lat, double lng, int precision) {
        if (precision < 1 || precision > 12) {
            throw new IllegalArgumentException("Precision must be 1-12, got: " + precision);
        }

        // Latitude and longitude ranges — we'll narrow these with each bit
        double latMin = -90.0, latMax = 90.0;
        double lngMin = -180.0, lngMax = 180.0;

        StringBuilder geohash = new StringBuilder();
        int bit = 0;          // current bit index within the 5-bit group
        int currentChar = 0;  // accumulates 5 bits before converting to base32
        boolean isLng = true; // start with longitude (convention)

        // We need precision * 5 total bits
        int totalBits = precision * 5;

        for (int i = 0; i < totalBits; i++) {
            if (isLng) {
                // Subdivide longitude range
                double mid = (lngMin + lngMax) / 2;
                if (lng >= mid) {
                    // Point is in the upper half — output bit 1
                    currentChar |= (1 << (4 - bit));
                    lngMin = mid;
                } else {
                    // Point is in the lower half — output bit 0
                    lngMax = mid;
                }
            } else {
                // Subdivide latitude range
                double mid = (latMin + latMax) / 2;
                if (lat >= mid) {
                    currentChar |= (1 << (4 - bit));
                    latMin = mid;
                } else {
                    latMax = mid;
                }
            }

            // Alternate between longitude and latitude bits
            isLng = !isLng;

            bit++;
            // Every 5 bits, convert to a base32 character
            if (bit == 5) {
                geohash.append(BASE32.charAt(currentChar));
                bit = 0;
                currentChar = 0;
            }
        }

        return geohash.toString();
    }

    /**
     * Decode a geohash string back to a Location (center of the geohash cell).
     *
     * Reverse of encode:
     *   1. Convert each base32 character to 5 bits
     *   2. De-interleave longitude and latitude bits
     *   3. Use bits to narrow lat/lng ranges
     *   4. Return the center of the final range
     */
    public static Location decode(String geohash) {
        if (geohash == null || geohash.isEmpty()) {
            throw new IllegalArgumentException("Geohash cannot be null or empty");
        }

        double latMin = -90.0, latMax = 90.0;
        double lngMin = -180.0, lngMax = 180.0;
        boolean isLng = true;

        for (char c : geohash.toCharArray()) {
            int charIndex = BASE32.indexOf(c);
            if (charIndex < 0) {
                throw new IllegalArgumentException("Invalid geohash character: " + c);
            }

            // Extract 5 bits from this character
            for (int bit = 4; bit >= 0; bit--) {
                boolean bitIsSet = ((charIndex >> bit) & 1) == 1;

                if (isLng) {
                    double mid = (lngMin + lngMax) / 2;
                    if (bitIsSet) {
                        lngMin = mid;
                    } else {
                        lngMax = mid;
                    }
                } else {
                    double mid = (latMin + latMax) / 2;
                    if (bitIsSet) {
                        latMin = mid;
                    } else {
                        latMax = mid;
                    }
                }
                isLng = !isLng;
            }
        }

        // Return center of the decoded range
        double lat = (latMin + latMax) / 2;
        double lng = (lngMin + lngMax) / 2;
        return new Location(lat, lng);
    }

    /**
     * Get the 8 neighboring geohash cells.
     *
     * WHY we need neighbors:
     *   A point near the edge of a geohash cell might be closest to points
     *   in adjacent cells. Without checking neighbors, we'd miss nearby drivers
     *   who happen to be just across the cell boundary.
     *
     *   ┌───┬───┬───┐
     *   │NW │ N │NE │
     *   ├───┼───┼───┤
     *   │ W │ X │ E │    X = current cell
     *   ├───┼───┼───┤    Check X + all 8 neighbors
     *   │SW │ S │SE │
     *   └───┴───┴───┘
     *
     * Algorithm: decode the geohash to get center + cell size, then
     * compute the 8 adjacent centers and re-encode them.
     */
    public static List<String> getNeighbors(String geohash) {
        if (geohash == null || geohash.isEmpty()) {
            throw new IllegalArgumentException("Geohash cannot be null or empty");
        }

        int precision = geohash.length();

        // Decode to get the center and approximate cell dimensions
        // Cell size depends on precision:
        //   lat range = 180 / 2^(totalLatBits)
        //   lng range = 360 / 2^(totalLngBits)
        int totalBits = precision * 5;
        int lngBits = (totalBits + 1) / 2;  // longitude gets the extra bit if odd
        int latBits = totalBits / 2;

        double latStep = 180.0 / Math.pow(2, latBits);
        double lngStep = 360.0 / Math.pow(2, lngBits);

        Location center = decode(geohash);
        double centerLat = center.getLat();
        double centerLng = center.getLng();

        // Generate 8 neighbors by stepping in each direction
        List<String> neighbors = new ArrayList<>();
        int[][] directions = {
                {-1,  1}, { 0,  1}, { 1,  1},   // SW, S, SE  (note: lat direction convention)
                {-1,  0},           { 1,  0},    // W, E
                {-1, -1}, { 0, -1}, { 1, -1}    // NW, N, NE
        };

        for (int[] dir : directions) {
            double neighborLat = centerLat + dir[1] * latStep;
            double neighborLng = centerLng + dir[0] * lngStep;

            // Clamp to valid ranges
            neighborLat = Math.max(-89.999, Math.min(89.999, neighborLat));
            neighborLng = Math.max(-179.999, Math.min(179.999, neighborLng));

            neighbors.add(encode(neighborLat, neighborLng, precision));
        }

        return neighbors;
    }
}
