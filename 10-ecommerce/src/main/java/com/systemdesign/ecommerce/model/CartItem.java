package com.systemdesign.ecommerce.model;

/**
 * CartItem — A single line in a shopping cart.
 *
 * Interview notes:
 * - priceSnapshot captures the price at the moment the item was added.
 *   This protects the customer from price changes between "add to cart"
 *   and "checkout". In a real system you'd re-validate at checkout time,
 *   but the snapshot gives a clear UX ("price when you added it").
 * - getSubtotal() is derived — no stored field, always consistent.
 */
public class CartItem {

    private final Product product;
    private int quantity;
    private final double priceSnapshot; // captured at add-time

    public CartItem(Product product, int quantity) {
        this.product = product;
        this.quantity = quantity;
        // Snapshot the price NOW so later catalog price changes don't
        // silently alter the cart total.
        this.priceSnapshot = product.getPrice();
    }

    // ── Derived ──────────────────────────────────────────────────────────

    /**
     * Subtotal = priceSnapshot * quantity.
     * Uses the snapshotted price, NOT the current product price.
     */
    public double getSubtotal() {
        return priceSnapshot * quantity;
    }

    // ── Getters / Setters ────────────────────────────────────────────────

    public Product getProduct()      { return product; }
    public int getQuantity()         { return quantity; }
    public double getPriceSnapshot() { return priceSnapshot; }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    @Override
    public String toString() {
        return String.format("CartItem{product='%s', qty=%d, snapshot=$%.2f, subtotal=$%.2f}",
                product.getName(), quantity, priceSnapshot, getSubtotal());
    }
}
