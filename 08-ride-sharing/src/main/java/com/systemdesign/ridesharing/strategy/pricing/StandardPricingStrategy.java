package com.systemdesign.ridesharing.strategy.pricing;

import com.systemdesign.ridesharing.model.Vehicle;

import java.util.EnumMap;
import java.util.Map;

/**
 * StandardPricingStrategy — Base fare + per-km + per-minute rate, by vehicle type.
 *
 * Rate table:
 *   ┌────────────────┬──────────┬──────────┬───────────┐
 *   │ Vehicle Type   │ Base ($) │ $/km     │ $/min     │
 *   ├────────────────┼──────────┼──────────┼───────────┤
 *   │ SEDAN          │ 2.00     │ 1.50     │ 0.25      │
 *   │ SUV            │ 3.50     │ 2.00     │ 0.35      │
 *   │ POOL           │ 1.50     │ 0.80     │ 0.15      │
 *   │ LUXURY         │ 5.00     │ 3.00     │ 0.50      │
 *   │ AUTO_RICKSHAW  │ 1.00     │ 0.50     │ 0.10      │
 *   └────────────────┴──────────┴──────────┴───────────┘
 *
 * Formula:
 *   fare = baseFare + (distanceKm * perKmRate) + (durationMinutes * perMinRate)
 *
 * NOTE: This strategy does NOT apply surge — that's handled by SurgePricingStrategy
 * which wraps this one (Decorator pattern).
 */
public class StandardPricingStrategy implements PricingStrategy {

    /** Pricing rates for each vehicle type: [baseFare, perKmRate, perMinRate]. */
    private final Map<Vehicle.VehicleType, double[]> rateTable;

    public StandardPricingStrategy() {
        rateTable = new EnumMap<>(Vehicle.VehicleType.class);
        //                           baseFare  perKm  perMin
        rateTable.put(Vehicle.VehicleType.SEDAN,          new double[]{2.00, 1.50, 0.25});
        rateTable.put(Vehicle.VehicleType.SUV,            new double[]{3.50, 2.00, 0.35});
        rateTable.put(Vehicle.VehicleType.POOL,           new double[]{1.50, 0.80, 0.15});
        rateTable.put(Vehicle.VehicleType.LUXURY,         new double[]{5.00, 3.00, 0.50});
        rateTable.put(Vehicle.VehicleType.AUTO_RICKSHAW,  new double[]{1.00, 0.50, 0.10});
    }

    /**
     * Calculate fare WITHOUT surge.
     *
     * The surgeMultiplier parameter is accepted (interface contract) but IGNORED here.
     * SurgePricingStrategy handles surge multiplication.
     * This separation follows Single Responsibility: StandardPricingStrategy knows RATES,
     * SurgePricingStrategy knows SURGE LOGIC.
     */
    @Override
    public double calculateFare(double distanceKm, double durationMinutes,
                                Vehicle.VehicleType type, double surgeMultiplier) {
        double[] rates = rateTable.get(type);
        if (rates == null) {
            // Fallback to SEDAN rates if unknown type
            rates = rateTable.get(Vehicle.VehicleType.SEDAN);
        }

        double baseFare = rates[0];
        double perKmRate = rates[1];
        double perMinRate = rates[2];

        // fare = base + (distance * per-km rate) + (duration * per-minute rate)
        double fare = baseFare + (distanceKm * perKmRate) + (durationMinutes * perMinRate);

        // Minimum fare: at least the base fare (you don't ride for free)
        return Math.max(fare, baseFare);
    }

    /**
     * Get the rate breakdown for display purposes.
     */
    public double[] getRates(Vehicle.VehicleType type) {
        return rateTable.getOrDefault(type, rateTable.get(Vehicle.VehicleType.SEDAN));
    }

    @Override
    public String toString() {
        return "StandardPricingStrategy";
    }
}
