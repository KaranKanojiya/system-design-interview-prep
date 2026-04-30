package com.systemdesign.ecommerce.strategy.shipping;

/**
 * StandardShippingStrategy — $5.99 flat rate, free over $50. 5-7 business days.
 *
 * Interview notes:
 * - Free shipping threshold ($50) incentivizes customers to add more items,
 *   increasing average order value (AOV). Amazon's $25 free shipping threshold
 *   was a major driver of basket size growth.
 * - "5-7 days" is a worst-case estimate; real systems compute from warehouse
 *   location + carrier SLA.
 */
public class StandardShippingStrategy implements ShippingStrategy {

    private static final double FLAT_RATE = 5.99;
    private static final double FREE_SHIPPING_THRESHOLD = 50.0;

    @Override
    public double calculateShippingCost(int itemCount, double orderTotal) {
        if (orderTotal >= FREE_SHIPPING_THRESHOLD) {
            return 0.0;  // Free shipping for orders >= $50
        }
        return FLAT_RATE;
    }

    @Override
    public int getEstimatedDays() {
        return 7; // 5-7 days, we return the upper bound
    }

    @Override
    public String toString() {
        return String.format("Standard Shipping ($%.2f, free over $%.0f, %d days)",
                FLAT_RATE, FREE_SHIPPING_THRESHOLD, getEstimatedDays());
    }
}
