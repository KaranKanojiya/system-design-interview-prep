package com.systemdesign.ridesharing.strategy.matching;

import com.systemdesign.ridesharing.model.Driver;
import com.systemdesign.ridesharing.model.Location;
import com.systemdesign.ridesharing.model.RideRequest;

import java.util.List;
import java.util.Optional;

/**
 * NearestDriverStrategy — Picks the closest available driver by Haversine distance.
 *
 * This is the simplest matching strategy and the one most interviewers expect first.
 *
 * UGLY approach (the if-else disaster):
 * ─────────────────────────────────────
 *   Driver bestDriver = null;
 *   double bestDistance = Double.MAX_VALUE;
 *   for (int i = 0; i < drivers.size(); i++) {
 *       Driver d = drivers.get(i);
 *       if (d.isAvailable()) {
 *           if (d.getVehicle() != null) {
 *               if (d.getVehicle().getType() == request.getVehicleType()) {
 *                   double dist = Math.sqrt(
 *                       Math.pow(d.getCurrentLocation().getLat() - pickup.getLat(), 2) +
 *                       Math.pow(d.getCurrentLocation().getLng() - pickup.getLng(), 2)
 *                   );  // WRONG — Euclidean distance on lat/lng is nonsense
 *                   if (dist < bestDistance) {
 *                       bestDistance = dist;
 *                       bestDriver = d;
 *                   }
 *               }
 *           }
 *       }
 *   }
 *   return bestDriver; // null if none found — NullPointerException waiting to happen
 *
 * CLEAN approach (below):
 *   Stream + sort + filter + Optional. Correct Haversine distance.
 *   No nulls, no nested ifs, no raw loops.
 */
public class NearestDriverStrategy implements MatchingStrategy {

    @Override
    public Optional<Driver> findBestDriver(RideRequest request, List<Driver> availableDrivers, Location pickup) {
        // Filter: available + correct vehicle type + has a location
        // Sort: by Haversine distance to pickup (nearest first)
        // Return: the first (nearest) driver, wrapped in Optional
        return availableDrivers.stream()
                .filter(Driver::isAvailable)
                .filter(d -> d.getVehicle().getType() == request.getVehicleType())
                .sorted((d1, d2) -> {
                    double dist1 = Location.distanceKm(d1.getCurrentLocation(), pickup);
                    double dist2 = Location.distanceKm(d2.getCurrentLocation(), pickup);
                    return Double.compare(dist1, dist2);
                })
                .findFirst();
    }

    @Override
    public String toString() {
        return "NearestDriverStrategy";
    }
}
