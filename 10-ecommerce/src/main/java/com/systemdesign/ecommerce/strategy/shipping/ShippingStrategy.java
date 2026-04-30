package com.systemdesign.ecommerce.strategy.shipping;

/**
 * ShippingStrategy — Strategy pattern for shipping cost and delivery estimation.
 *
 * Interview notes:
 * - Decouples shipping logic from the order flow. The customer selects a
 *   strategy at checkout; the order total = item total + shipping cost.
 * - In production, shipping cost depends on weight, dimensions, origin/dest
 *   zip codes, carrier rates, etc. We simplify to itemCount + orderTotal.
 */
public interface ShippingStrategy {

    /**
     * Calculates shipping cost given the number of items and order subtotal.
     */
    double calculateShippingCost(int itemCount, double orderTotal);

    /**
     * Estimated delivery window in days.
     */
    int getEstimatedDays();
}
