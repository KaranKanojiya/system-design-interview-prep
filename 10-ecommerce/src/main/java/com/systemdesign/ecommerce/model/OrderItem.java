package com.systemdesign.ecommerce.model;

/**
 * OrderItem — An immutable snapshot of a product line within an Order.
 *
 * Interview notes:
 * - Unlike CartItem, this stores productId/productName as plain strings
 *   rather than holding a Product reference. Once an order is placed the
 *   line items are "frozen" — if the catalog product is later renamed or
 *   deleted, the order history remains accurate.
 * - subtotal is pre-computed for fast aggregation (denormalization trade-off).
 */
public class OrderItem {

    private final String productId;
    private final String productName;
    private final int quantity;
    private final double unitPrice;
    private final double subtotal;

    public OrderItem(String productId, String productName,
                     int quantity, double unitPrice) {
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.subtotal = unitPrice * quantity;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getProductId()   { return productId; }
    public String getProductName() { return productName; }
    public int getQuantity()       { return quantity; }
    public double getUnitPrice()   { return unitPrice; }
    public double getSubtotal()    { return subtotal; }

    @Override
    public String toString() {
        return String.format("OrderItem{product='%s', qty=%d, unit=$%.2f, sub=$%.2f}",
                productName, quantity, unitPrice, subtotal);
    }
}
