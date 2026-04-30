package com.systemdesign.ecommerce.strategy.pricing;

import com.systemdesign.ecommerce.model.OrderItem;

import java.util.List;

/**
 * DiscountPricingStrategy — Decorator that wraps another PricingStrategy
 * and applies discount rules on top.
 *
 * Interview notes:
 * - Decorator pattern: this class IS-A PricingStrategy and HAS-A
 *   PricingStrategy (the delegate). The caller doesn't know whether
 *   discounts are applied — it just calls calculatePrice().
 *
 * Discount rules:
 *   1) 10% off orders over $100 (incentivize larger baskets)
 *   2) 5% off orders with 5+ items (bulk purchase reward)
 *   3) Rules stack: a $120 order with 6 items gets 10% + 5% = 15% off
 *
 * In production, discount rules would come from a rules engine or config
 * service, not hard-coded constants. But for interview purposes, clear
 * inline logic is preferable to over-abstraction.
 */
public class DiscountPricingStrategy implements PricingStrategy {

    private static final double HIGH_VALUE_THRESHOLD = 100.0;
    private static final double HIGH_VALUE_DISCOUNT = 0.10;   // 10%
    private static final int BULK_ITEM_THRESHOLD = 5;
    private static final double BULK_DISCOUNT = 0.05;         // 5%

    private final PricingStrategy delegate;

    public DiscountPricingStrategy(PricingStrategy delegate) {
        this.delegate = delegate;
    }

    @Override
    public double calculatePrice(List<OrderItem> items) {
        // Start with the delegate's price (e.g., StandardPricingStrategy)
        double basePrice = delegate.calculatePrice(items);
        double totalDiscount = 0.0;

        int totalItems = items.stream().mapToInt(OrderItem::getQuantity).sum();

        // Rule 1: 10% off orders > $100
        if (basePrice > HIGH_VALUE_THRESHOLD) {
            double discount = basePrice * HIGH_VALUE_DISCOUNT;
            totalDiscount += discount;
            System.out.printf("    [Discount] 10%% off (order > $%.0f): -$%.2f%n",
                    HIGH_VALUE_THRESHOLD, discount);
        }

        // Rule 2: 5% off orders with 5+ items
        if (totalItems >= BULK_ITEM_THRESHOLD) {
            double discount = basePrice * BULK_DISCOUNT;
            totalDiscount += discount;
            System.out.printf("    [Discount] 5%% off (bulk: %d items): -$%.2f%n",
                    totalItems, discount);
        }

        double finalPrice = basePrice - totalDiscount;

        if (totalDiscount > 0) {
            System.out.printf("    [Discount] Base=$%.2f, Discount=$%.2f, Final=$%.2f%n",
                    basePrice, totalDiscount, finalPrice);
        }

        return finalPrice;
    }
}
