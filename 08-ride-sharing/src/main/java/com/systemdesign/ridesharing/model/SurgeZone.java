package com.systemdesign.ridesharing.model;

/**
 * SurgeZone — A geographic zone where surge pricing may apply.
 *
 * HOW surge pricing works in Uber/Lyft:
 *   The city is divided into hexagonal (or rectangular) zones. Each zone tracks:
 *   - Supply: how many available drivers are in the zone
 *   - Demand: how many pending ride requests are in the zone
 *   When demand outstrips supply, the surge multiplier goes up.
 *
 * WHY surge pricing exists:
 *   1. Incentivizes drivers to move to high-demand areas (earn more)
 *   2. Reduces demand from price-sensitive riders (some will wait or take transit)
 *   3. Achieves market equilibrium — the price adjusts until supply meets demand
 *
 * Tier system (used in this implementation):
 *   demand/supply ratio  | multiplier
 *   ---------------------|----------
 *   <= 1.0               | 1.0x (no surge)
 *   1.0 - 1.5            | 1.25x
 *   1.5 - 2.0            | 1.5x
 *   2.0 - 2.5            | 2.0x
 *   2.5 - 3.0            | 2.5x
 *   > 3.0                | 3.0x (cap)
 *
 * In production, the algorithm is more sophisticated (ML-based, considers
 * time of day, events, weather, historical patterns).
 */
public class SurgeZone {

    private final String zoneId;
    private final Location centerLocation;
    private final double radiusKm;
    private int supplyCount;   // available drivers in this zone
    private int demandCount;   // pending ride requests in this zone
    private double surgeMultiplier;

    public SurgeZone(String zoneId, Location centerLocation, double radiusKm) {
        this.zoneId = zoneId;
        this.centerLocation = centerLocation;
        this.radiusKm = radiusKm;
        this.supplyCount = 0;
        this.demandCount = 0;
        this.surgeMultiplier = 1.0;
    }

    /**
     * Calculate the surge multiplier based on current supply/demand.
     *
     * UGLY approach:
     *   return demandCount / supplyCount;
     *   // Problems: division by zero, linear scaling (4x demand = 4x price is extreme),
     *   // no cap (10x surge = PR nightmare)
     *
     * CLEAN approach:
     *   Tiered multiplier with a cap. Feels fair to riders, still incentivizes drivers.
     */
    public double calculateMultiplier() {
        // Edge case: no demand means no surge
        if (demandCount == 0) {
            this.surgeMultiplier = 1.0;
            return surgeMultiplier;
        }

        // Edge case: no supply but there IS demand — max surge to attract drivers
        if (supplyCount == 0) {
            this.surgeMultiplier = 3.0;
            return surgeMultiplier;
        }

        // Supply meets or exceeds demand — no surge needed
        if (supplyCount >= demandCount) {
            this.surgeMultiplier = 1.0;
            return surgeMultiplier;
        }

        // Calculate demand-to-supply ratio
        double ratio = (double) demandCount / supplyCount;

        // Tiered surge — each tier adds more urgency to attract drivers
        if (ratio <= 1.5) {
            this.surgeMultiplier = 1.25;
        } else if (ratio <= 2.0) {
            this.surgeMultiplier = 1.5;
        } else if (ratio <= 2.5) {
            this.surgeMultiplier = 2.0;
        } else if (ratio <= 3.0) {
            this.surgeMultiplier = 2.5;
        } else {
            this.surgeMultiplier = 3.0;  // hard cap — Uber learned the hard way
        }

        return surgeMultiplier;
    }

    /**
     * Check if a location falls within this surge zone.
     */
    public boolean containsLocation(Location location) {
        return Location.distanceKm(centerLocation, location) <= radiusKm;
    }

    // --- Getters & Setters ---

    public String getZoneId() {
        return zoneId;
    }

    public Location getCenterLocation() {
        return centerLocation;
    }

    public double getRadiusKm() {
        return radiusKm;
    }

    public int getSupplyCount() {
        return supplyCount;
    }

    public void setSupplyCount(int supplyCount) {
        this.supplyCount = supplyCount;
    }

    public int getDemandCount() {
        return demandCount;
    }

    public void setDemandCount(int demandCount) {
        this.demandCount = demandCount;
    }

    public double getSurgeMultiplier() {
        return surgeMultiplier;
    }

    @Override
    public String toString() {
        return String.format("SurgeZone{id='%s', center=%s, radius=%.1fkm, supply=%d, demand=%d, surge=%.2fx}",
                zoneId, centerLocation, radiusKm, supplyCount, demandCount, surgeMultiplier);
    }
}
