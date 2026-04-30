package com.systemdesign.ridesharing.strategy.pricing;

import com.systemdesign.ridesharing.model.Vehicle;

/**
 * SurgePricingStrategy — Decorator around StandardPricingStrategy.
 *
 * PATTERN: Decorator
 *   This wraps the StandardPricingStrategy and multiplies the result by the
 *   surge multiplier. The caller doesn't need to know whether surge is applied;
 *   they just call calculateFare() and get the right answer.
 *
 * WHY Decorator instead of inheritance:
 *   - StandardPricingStrategy is still usable on its own (no surge)
 *   - Surge logic is isolated — can be changed without touching rate calculations
 *   - Can be composed at runtime: sometimes surge, sometimes not
 *   - In production, you might stack decorators: surge * promo * subscription
 *
 * SURGE CAP:
 *   Capped at 3.0x to prevent PR disasters. Uber learned this the hard way
 *   during storms/emergencies — uncapped surge led to $300 rides for 2 miles.
 *   Now they cap surge and sometimes disable it during declared emergencies.
 */
public class SurgePricingStrategy implements PricingStrategy {

    /** Maximum allowed surge multiplier — hard cap. */
    private static final double MAX_SURGE = 3.0;

    /** The wrapped standard pricing strategy — provides base fare calculation. */
    private final StandardPricingStrategy standardPricing;

    public SurgePricingStrategy(StandardPricingStrategy standardPricing) {
        this.standardPricing = standardPricing;
    }

    /**
     * Calculate fare with surge pricing applied.
     *
     * Steps:
     *   1. Calculate the standard fare (no surge)
     *   2. Cap the surge multiplier at MAX_SURGE
     *   3. Multiply: surgedFare = standardFare * cappedSurge
     *   4. Print the breakdown (for demo visibility)
     *
     * @param surgeMultiplier the raw surge multiplier from SurgeService
     */
    @Override
    public double calculateFare(double distanceKm, double durationMinutes,
                                Vehicle.VehicleType type, double surgeMultiplier) {
        // Step 1: Get the base fare without surge
        double standardFare = standardPricing.calculateFare(distanceKm, durationMinutes, type, 1.0);

        // Step 2: Cap the surge multiplier
        double cappedSurge = Math.min(surgeMultiplier, MAX_SURGE);

        // Step 3: Apply surge
        double surgedFare = standardFare * cappedSurge;

        // Step 4: Print breakdown (helpful for demo output)
        if (cappedSurge > 1.0) {
            double[] rates = standardPricing.getRates(type);
            System.out.printf("  [Fare Breakdown] %s ride: base=$%.2f + %.1fkm*$%.2f/km + %.0fmin*$%.2f/min = $%.2f%n",
                    type, rates[0], distanceKm, rates[1], durationMinutes, rates[2], standardFare);
            System.out.printf("  [Surge Applied]  $%.2f * %.2fx surge = $%.2f%n",
                    standardFare, cappedSurge, surgedFare);
            if (surgeMultiplier > MAX_SURGE) {
                System.out.printf("  [Surge Capped]   Requested %.2fx, capped at %.1fx%n",
                        surgeMultiplier, MAX_SURGE);
            }
        }

        return surgedFare;
    }

    /** Get the underlying standard pricing strategy. */
    public StandardPricingStrategy getStandardPricing() {
        return standardPricing;
    }

    @Override
    public String toString() {
        return "SurgePricingStrategy (wrapping " + standardPricing + ")";
    }
}
