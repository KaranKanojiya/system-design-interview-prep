package com.systemdesign.ecommerce.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cart — A user's shopping cart, aggregating CartItems.
 *
 * Interview notes:
 * - Builder pattern for clean construction.
 * - addItem merges quantities if the same product is added again, rather
 *   than creating a duplicate line — standard e-commerce behavior.
 * - removeItem / updateQuantity locate by productId, not by object reference,
 *   because the caller may hold a different Product instance.
 * - updatedAt is mutated on every write operation so the system can detect
 *   stale carts (e.g., abandon-cart emails after 24h of inactivity).
 */
public class Cart {

    private final String cartId;
    private final String userId;
    private final List<CartItem> items;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Cart(Builder builder) {
        this.cartId = builder.cartId;
        this.userId = builder.userId;
        this.items = builder.items;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    // ── Cart operations ──────────────────────────────────────────────────

    /**
     * Adds a product to the cart. If the product already exists, increments
     * quantity instead of creating a duplicate CartItem.
     */
    public void addItem(Product product, int quantity) {
        Optional<CartItem> existing = findItem(product.getId());
        if (existing.isPresent()) {
            // Merge: just bump the quantity on the existing line
            CartItem item = existing.get();
            item.setQuantity(item.getQuantity() + quantity);
        } else {
            items.add(new CartItem(product, quantity));
        }
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Removes the item for the given productId entirely from the cart.
     */
    public void removeItem(String productId) {
        items.removeIf(item -> item.getProduct().getId().equals(productId));
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Sets the quantity for an existing item. If newQty <= 0, removes the item.
     */
    public void updateQuantity(String productId, int newQty) {
        if (newQty <= 0) {
            removeItem(productId);
            return;
        }
        findItem(productId).ifPresent(item -> {
            item.setQuantity(newQty);
            this.updatedAt = LocalDateTime.now();
        });
    }

    /**
     * Sum of all line subtotals.
     */
    public double getTotal() {
        return items.stream()
                .mapToDouble(CartItem::getSubtotal)
                .sum();
    }

    /**
     * Removes every item from the cart (post-checkout).
     */
    public void clear() {
        items.clear();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Total number of individual items (sum of quantities, not distinct SKUs).
     */
    public int getItemCount() {
        return items.stream()
                .mapToInt(CartItem::getQuantity)
                .sum();
    }

    // ── Internal helper ──────────────────────────────────────────────────

    private Optional<CartItem> findItem(String productId) {
        return items.stream()
                .filter(i -> i.getProduct().getId().equals(productId))
                .findFirst();
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getCartId()            { return cartId; }
    public String getUserId()            { return userId; }
    public List<CartItem> getItems()     { return items; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public LocalDateTime getUpdatedAt()  { return updatedAt; }

    @Override
    public String toString() {
        return String.format("Cart{id='%s', userId='%s', items=%d, total=$%.2f}",
                cartId, userId, getItemCount(), getTotal());
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static class Builder {
        private String cartId;
        private String userId;
        private List<CartItem> items = new ArrayList<>();
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt = LocalDateTime.now();

        public Builder cartId(String cartId)     { this.cartId = cartId; return this; }
        public Builder userId(String userId)     { this.userId = userId; return this; }
        public Builder items(List<CartItem> items) { this.items = items; return this; }
        public Builder createdAt(LocalDateTime t) { this.createdAt = t; return this; }
        public Builder updatedAt(LocalDateTime t) { this.updatedAt = t; return this; }

        public Cart build() {
            return new Cart(this);
        }
    }
}
