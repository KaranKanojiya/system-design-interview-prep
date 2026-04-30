package com.systemdesign.trading.engine;

import com.systemdesign.trading.model.Order;

import java.util.Iterator;
import java.util.LinkedList;

/**
 * PriceLevel represents all orders at a single price point in the order book.
 *
 * Each price level is a FIFO queue. Orders at the same price are served
 * first-come-first-served (time priority). This is the "price-time priority"
 * matching rule used by most exchanges worldwide (NSE, NYSE, NASDAQ).
 *
 * WHY LinkedList (not ArrayList):
 * - Orders are added at the TAIL and removed from the HEAD (FIFO).
 * - LinkedList: O(1) addLast, O(1) removeFirst — perfect for a queue.
 * - ArrayList: O(1) add, but O(N) remove from front (shifts all elements).
 * - We never do random access by index, so LinkedList wins.
 *
 * WHY totalQuantity is maintained (not computed):
 * - Computing sum of all order quantities on every access would be O(N).
 * - By maintaining it incrementally, getQuantity() is O(1).
 * - Trade-off: slightly more code, but order book depth queries are fast.
 *
 * CALL CHAIN:
 * OrderBook.addOrder() → finds/creates PriceLevel → PriceLevel.addOrder() →
 * MatchingEngine matches → PriceLevel.peekFirst() to see best order →
 * after fill, if order fully filled → PriceLevel.removeFirst()
 */
public class PriceLevel {

    private final double price;
    private final LinkedList<Order> orders;  // FIFO queue: head = earliest order
    private int totalQuantity;               // Sum of all orders' remaining quantities

    public PriceLevel(double price) {
        this.price = price;
        this.orders = new LinkedList<>();
        this.totalQuantity = 0;
    }

    /**
     * Add an order to the BACK of the queue (newest order has lowest time priority).
     */
    public void addOrder(Order order) {
        orders.addLast(order);
        totalQuantity += order.getRemainingQuantity();
    }

    /**
     * Remove and return the FIRST order (highest time priority — was placed earliest).
     * Used when the front order is fully filled and needs to be removed from the book.
     */
    public Order removeFirst() {
        if (orders.isEmpty()) return null;
        Order removed = orders.removeFirst();
        totalQuantity -= removed.getRemainingQuantity();
        return removed;
    }

    /**
     * Peek at the first order without removing it.
     * Used during matching to check the best available order at this price.
     */
    public Order peekFirst() {
        return orders.isEmpty() ? null : orders.getFirst();
    }

    /**
     * Remove a specific order by ID (for cancellation).
     * O(N) scan — acceptable because cancellations are less frequent than matches.
     */
    public boolean removeOrder(String orderId) {
        Iterator<Order> it = orders.iterator();
        while (it.hasNext()) {
            Order order = it.next();
            if (order.getOrderId().equals(orderId)) {
                totalQuantity -= order.getRemainingQuantity();
                it.remove();
                return true;
            }
        }
        return false;
    }

    /**
     * Update totalQuantity after a partial fill.
     * WHY: When an order at this level is partially filled, its remaining quantity decreases
     * but the order stays in the queue. We need to reflect this in totalQuantity.
     */
    public void updateQuantityAfterFill(int filledQty) {
        totalQuantity -= filledQty;
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    public double getPrice() { return price; }
    public int getTotalQuantity() { return totalQuantity; }
    public int getOrderCount() { return orders.size(); }

    @Override
    public String toString() {
        return String.format("PriceLevel{price=%.2f, qty=%d, orders=%d}",
                price, totalQuantity, orders.size());
    }
}
