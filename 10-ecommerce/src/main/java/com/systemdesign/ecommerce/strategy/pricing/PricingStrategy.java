package com.systemdesign.ecommerce.strategy.pricing;

import com.systemdesign.ecommerce.model.OrderItem;

import java.util.List;

/**
 * PricingStrategy — Strategy pattern for order pricing.
 *
 * Interview notes:
 * - Separates "how to calculate the total" from the order itself.
 * - Enables composable pricing: StandardPricingStrategy computes the base,
 *   DiscountPricingStrategy wraps it as a decorator and applies rules.
 * - New pricing rules (loyalty discount, coupon codes, dynamic pricing)
 *   can be added without touching existing code (Open/Closed Principle).
 */
public interface PricingStrategy {

    /**
     * Calculates the total price for the given order items.
     */
    double calculatePrice(List<OrderItem> items);
}
