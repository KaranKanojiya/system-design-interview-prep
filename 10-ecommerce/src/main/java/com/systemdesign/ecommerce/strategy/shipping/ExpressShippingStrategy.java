package com.systemdesign.ecommerce.strategy.shipping;

/**
 * ExpressShippingStrategy — $14.99, 1-2 business days.
 *
 * Interview notes:
 * - No free-shipping threshold — express always costs money.
 * - In production, express shipping might be free for Prime members
 *   (a membership strategy that shifts cost from per-order to subscription).
 * - Higher margin for the platform: the actual carrier surcharge for
 *   express is often less than $14.99.
 */
public class ExpressShippingStrategy implements ShippingStrategy {

    private static final double EXPRESS_RATE = 14.99;

    @Override
    public double calculateShippingCost(int itemCount, double orderTotal) {
        return EXPRESS_RATE;  // Flat rate regardless of order size
    }

    @Override
    public int getEstimatedDays() {
        return 2; // 1-2 days, we return the upper bound
    }

    @Override
    public String toString() {
        return String.format("Express Shipping ($%.2f, %d days)", EXPRESS_RATE, getEstimatedDays());
    }
}
