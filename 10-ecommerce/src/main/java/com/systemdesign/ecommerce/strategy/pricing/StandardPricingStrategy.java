package com.systemdesign.ecommerce.strategy.pricing;

import com.systemdesign.ecommerce.model.OrderItem;

import java.util.List;

/**
 * StandardPricingStrategy — Simply sums unitPrice * quantity for all items.
 *
 * This is the base case; no discounts, no tax, no shipping.
 * Acts as the "leaf" in a decorator chain when wrapped by
 * DiscountPricingStrategy.
 */
public class StandardPricingStrategy implements PricingStrategy {

    @Override
    public double calculatePrice(List<OrderItem> items) {
        return items.stream()
                .mapToDouble(item -> item.getUnitPrice() * item.getQuantity())
                .sum();
    }
}
