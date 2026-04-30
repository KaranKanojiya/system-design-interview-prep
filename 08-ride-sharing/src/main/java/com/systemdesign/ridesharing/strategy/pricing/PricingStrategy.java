package com.systemdesign.ridesharing.strategy.pricing;

import com.systemdesign.ridesharing.model.Vehicle;

/**
 * PricingStrategy — Strategy pattern for fare calculation.
 *
 * WHY Strategy pattern:
 *   Different pricing models can be swapped without touching business logic:
 *   - StandardPricingStrategy: base fare + per-km + per-min
 *   - SurgePricingStrategy: wraps standard pricing with a multiplier (Decorator-like)
 *   - In production: ML-based dynamic pricing, subscription discounts, promo codes, etc.
 *
 * INTERVIEW NOTE:
 *   "Pricing in ride-sharing is a two-phase process:
 *    Phase 1 (before ride): ESTIMATE — shown to rider for confirmation.
 *      Uses Haversine distance + estimated duration. This is a promise.
 *    Phase 2 (after ride): ACTUAL — computed from GPS trace.
 *      Uses actual route distance + actual time. If actual < estimate,
 *      rider pays the actual. If actual > estimate (e.g., driver took a detour),
 *      Uber sometimes caps at the estimate to protect the rider."
 */
public interface PricingStrategy {

    /**
     * Calculate the fare for a ride.
     *
     * @param distanceKm      distance in kilometers
     * @param durationMinutes duration in minutes
     * @param type            vehicle type (affects rate)
     * @param surgeMultiplier surge pricing multiplier (1.0 = no surge)
     * @return fare in dollars
     */
    double calculateFare(double distanceKm, double durationMinutes,
                         Vehicle.VehicleType type, double surgeMultiplier);
}
