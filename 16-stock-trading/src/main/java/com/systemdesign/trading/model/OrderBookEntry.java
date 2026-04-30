package com.systemdesign.trading.model;

/**
 * OrderBookEntry is a display-friendly view of one price level in the order book.
 *
 * WHY a separate class (not just PriceLevel):
 * - PriceLevel contains actual Order objects — internal engine state.
 * - OrderBookEntry is a SNAPSHOT for display: price, total qty, order count.
 * - This separation prevents leaking mutable internal state to clients.
 * - In a real system, this would be serialized to JSON for the frontend.
 *
 * EXAMPLE (top 3 bid levels):
 *   Price    | Qty    | Orders
 *   2505.00  | 500    | 3
 *   2504.50  | 200    | 1
 *   2504.00  | 1000   | 5
 *
 * CALL CHAIN:
 * TradingController.handleGetOrderBook() → OrderBook.getBidDepth(5) →
 * List<OrderBookEntry> → displayed to user
 */
public class OrderBookEntry {

    private final double price;
    private final int totalQuantity;
    private final int orderCount;

    public OrderBookEntry(double price, int totalQuantity, int orderCount) {
        this.price = price;
        this.totalQuantity = totalQuantity;
        this.orderCount = orderCount;
    }

    public double getPrice() { return price; }
    public int getTotalQuantity() { return totalQuantity; }
    public int getOrderCount() { return orderCount; }

    @Override
    public String toString() {
        return String.format("%.2f | %,d | %d orders", price, totalQuantity, orderCount);
    }
}
