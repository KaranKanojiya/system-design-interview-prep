package com.systemdesign.ecommerce.model;

import com.systemdesign.ecommerce.exception.InsufficientStockException;

/**
 * Inventory — Thread-safe stock management for a single product.
 *
 * Interview notes:
 * - Uses a two-phase reservation model:
 *     1) reserve(qty)              — decrements availableStock, increments reservedStock
 *     2) confirmReservation(qty)   — decrements both totalStock and reservedStock (stock is gone)
 *        OR
 *     2) releaseReservation(qty)   — decrements reservedStock only (stock goes back to available)
 *
 * - All mutating methods are synchronized on the Inventory instance to prevent
 *   overselling under concurrent checkout. In a distributed system you'd use
 *   an optimistic lock (version column) or Redis DECR, but the synchronized
 *   keyword faithfully models the "compare-and-set" semantic.
 *
 * - getAvailableStock() = totalStock - reservedStock. This is the quantity
 *   that can still be reserved by new orders.
 */
public class Inventory {

    private final String productId;
    private final String productName;
    private int totalStock;
    private int reservedStock;

    public Inventory(String productId, String productName, int totalStock) {
        this.productId = productId;
        this.productName = productName;
        this.totalStock = totalStock;
        this.reservedStock = 0;
    }

    // ── Derived ──────────────────────────────────────────────────────────

    /**
     * Stock available for new reservations.
     * availableStock = totalStock - reservedStock
     */
    public synchronized int getAvailableStock() {
        return totalStock - reservedStock;
    }

    // ── Reservation lifecycle (all synchronized) ─────────────────────────

    /**
     * Phase 1: Reserve stock for an order.
     *
     * Why synchronized? Two threads checking availableStock > qty at the same
     * time could both pass the check and both decrement, resulting in
     * reservedStock > totalStock (overselling). The synchronized block ensures
     * only one thread can read-then-write atomically.
     *
     * @throws InsufficientStockException if available stock < requested qty
     */
    public synchronized void reserve(int qty) {
        int available = getAvailableStock();
        if (available < qty) {
            throw new InsufficientStockException(productId, qty, available);
        }
        reservedStock += qty;
    }

    /**
     * Phase 2a: Confirm — the order shipped, stock is permanently consumed.
     * Decrements both totalStock and reservedStock by qty.
     */
    public synchronized void confirmReservation(int qty) {
        totalStock -= qty;
        reservedStock -= qty;
    }

    /**
     * Phase 2b: Release — the order was cancelled or payment failed.
     * Decrements reservedStock only; totalStock is unchanged so the
     * stock becomes available for other customers.
     */
    public synchronized void releaseReservation(int qty) {
        reservedStock -= qty;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getProductId()   { return productId; }
    public String getProductName() { return productName; }
    public int getTotalStock()     { return totalStock; }
    public int getReservedStock()  { return reservedStock; }

    @Override
    public String toString() {
        return String.format("Inventory{product='%s', total=%d, reserved=%d, available=%d}",
                productName, totalStock, reservedStock, getAvailableStock());
    }
}
