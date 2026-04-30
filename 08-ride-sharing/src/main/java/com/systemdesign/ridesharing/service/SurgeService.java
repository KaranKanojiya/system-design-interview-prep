package com.systemdesign.ridesharing.service;

import com.systemdesign.ridesharing.model.Location;
import com.systemdesign.ridesharing.model.SurgeZone;

import java.util.ArrayList;
import java.util.List;

/**
 * SurgeService — Manages surge pricing zones across the city.
 *
 * HOW SURGE WORKS (architecture):
 *   1. The city is divided into a grid of SurgeZones (rectangular cells)
 *   2. Each zone tracks supply (available drivers) and demand (pending requests)
 *   3. When demand > supply in a zone, the surge multiplier goes up
 *   4. The multiplier is applied to the fare estimate shown to the rider
 *
 * CALL CHAIN:
 *   RideService.requestRide()
 *     -> SurgeService.getSurgeMultiplier(pickup)
 *       -> Find the zone containing the pickup location
 *       -> Return that zone's current surge multiplier
 *     -> PricingService.estimateFare(..., surgeMultiplier)
 *
 * In production Uber:
 *   - Zones are hexagonal (H3) for uniform coverage
 *   - Supply/demand is updated in real-time (every few seconds)
 *   - Surge is computed by ML models (not just ratio-based)
 *   - The multiplier is smoothed over time (prevents rapid oscillation)
 *   - There's a map UI showing surge zones to drivers (incentivizes movement)
 *
 * GRID SETUP:
 *   We create a grid covering a city area (e.g., San Francisco).
 *   Each cell is ~2km x 2km. For a 20km x 20km city, that's 100 zones.
 */
public class SurgeService {

    /** Default zone radius in km. */
    private static final double DEFAULT_ZONE_RADIUS_KM = 2.0;

    private final List<SurgeZone> zones;

    public SurgeService() {
        this.zones = new ArrayList<>();
    }

    /**
     * Initialize the surge grid covering a city area.
     *
     * Creates a grid of zones from the given bounding box.
     * For a 20km x 20km city with 2km zones, that's ~100 zones.
     *
     * @param centerLat  center latitude of the city
     * @param centerLng  center longitude of the city
     * @param gridSizeKm total size of the grid in km (width and height)
     * @param zoneRadiusKm radius of each zone
     */
    public void initializeGrid(double centerLat, double centerLng,
                               double gridSizeKm, double zoneRadiusKm) {
        zones.clear();
        double zoneDiameter = zoneRadiusKm * 2;
        int zonesPerSide = (int) Math.ceil(gridSizeKm / zoneDiameter);

        // Convert km offsets to lat/lng deltas
        // 1 degree lat ~ 111 km, 1 degree lng ~ 111 * cos(lat) km
        double latPerKm = 1.0 / 111.0;
        double lngPerKm = 1.0 / (111.0 * Math.cos(Math.toRadians(centerLat)));

        double startLat = centerLat - (gridSizeKm / 2) * latPerKm;
        double startLng = centerLng - (gridSizeKm / 2) * lngPerKm;

        int zoneCount = 0;
        for (int row = 0; row < zonesPerSide; row++) {
            for (int col = 0; col < zonesPerSide; col++) {
                double zoneLat = startLat + (row * zoneDiameter + zoneRadiusKm) * latPerKm;
                double zoneLng = startLng + (col * zoneDiameter + zoneRadiusKm) * lngPerKm;

                // Clamp to valid ranges
                zoneLat = Math.max(-89.9, Math.min(89.9, zoneLat));
                zoneLng = Math.max(-179.9, Math.min(179.9, zoneLng));

                String zoneId = String.format("zone-%d-%d", row, col);
                zones.add(new SurgeZone(zoneId, new Location(zoneLat, zoneLng), zoneRadiusKm));
                zoneCount++;
            }
        }

        System.out.printf("  [SurgeService] Initialized %d surge zones (%.0fkm grid, %.0fkm zones)%n",
                zoneCount, gridSizeKm, zoneRadiusKm);
    }

    /**
     * Get the surge multiplier for a pickup location.
     *
     * Finds the zone containing the location and returns its surge multiplier.
     * If no zone contains the location, returns 1.0 (no surge).
     */
    public double getSurgeMultiplier(Location pickup) {
        for (SurgeZone zone : zones) {
            if (zone.containsLocation(pickup)) {
                return zone.calculateMultiplier();
            }
        }
        // No zone covers this location — no surge
        return 1.0;
    }

    /**
     * Update the supply/demand metrics for a specific zone.
     *
     * In production, this is called periodically (every 5-30 seconds) by a
     * background job that counts available drivers and pending requests
     * in each zone.
     */
    public void updateZoneMetrics(String zoneId, int supply, int demand) {
        for (SurgeZone zone : zones) {
            if (zone.getZoneId().equals(zoneId)) {
                zone.setSupplyCount(supply);
                zone.setDemandCount(demand);
                zone.calculateMultiplier();
                return;
            }
        }
    }

    /**
     * Calculate surge for a specific zone (by reference).
     */
    public double calculateZoneSurge(SurgeZone zone) {
        return zone.calculateMultiplier();
    }

    /**
     * Add a zone manually (for demo/testing).
     */
    public void addZone(SurgeZone zone) {
        zones.add(zone);
    }

    /** Get all surge zones (for display/debugging). */
    public List<SurgeZone> getZones() {
        return zones;
    }

    /**
     * Find the zone containing a location.
     */
    public SurgeZone findZone(Location location) {
        for (SurgeZone zone : zones) {
            if (zone.containsLocation(location)) {
                return zone;
            }
        }
        return null;
    }
}
