package com.systemdesign.trading.engine;

import com.systemdesign.trading.model.Order;
import com.systemdesign.trading.model.OrderBookEntry;
import com.systemdesign.trading.model.OrderSide;

import java.util.*;

/**
 * OrderBook is THE KEY DATA STRUCTURE of the trading system.
 *
 * It maintains two sorted collections of price levels:
 * - BIDS (buy orders): sorted DESCENDING by price. Best bid = highest price.
 * - ASKS (sell orders): sorted ASCENDING by price. Best ask = lowest price.
 *
 * WHY TreeMap is perfect:
 * 1. SORTED: Bids need highest-first, asks need lowest-first. TreeMap maintains sort order
 *    automatically via Comparator. No manual sorting needed.
 * 2. O(log N) insert/remove: Adding/removing price levels is O(log N) where N = number of
 *    distinct prices. In practice N is small (hundreds of price levels), so this is fast.
 * 3. firstEntry()/lastEntry() in O(log N): Getting the best bid/ask is a tree traversal
 *    to the leftmost/rightmost node. Constant for balanced trees.
 * 4. NavigableMap methods: We get floorEntry, ceilingEntry, headMap, tailMap for free.
 *    These are useful for range queries (e.g., "all bids above 2500").
 *
 * WHY NOT HashMap + sort:
 * - HashMap gives O(1) lookup by price but NO ordering.
 * - We'd need to sort on every best-bid/ask query → O(N log N) per query.
 * - TreeMap gives O(log N) per operation WITH ordering built in.
 *
 * WHY NOT PriorityQueue:
 * - PQ doesn't support efficient removal of arbitrary elements (cancellation).
 * - PQ doesn't support "get all orders at a specific price" (for depth display).
 * - TreeMap supports both via key-based operations.
 *
 * SPREAD = bestAsk - bestBid. Narrow spread = liquid market. Wide spread = illiquid.
 *
 * CALL CHAIN:
 * MatchingEngine.submitOrder() → OrderBook.getBestBid()/getBestAsk() for matching →
 * OrderBook.addOrder() for unmatched limit orders → OrderBook.removeOrder() for cancellations →
 * TradingController.handleGetOrderBook() → OrderBook.getBidDepth()/getAskDepth() for display
 */
public class OrderBook {

    private final String symbol;

    // Bids: highest price first (buyer willing to pay the most gets matched first)
    // Comparator.reverseOrder() makes TreeMap sort keys in DESCENDING order
    private final TreeMap<Double, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());

    // Asks: lowest price first (seller asking the least gets matched first)
    // Natural ordering (ascending) — default TreeMap behavior
    private final TreeMap<Double, PriceLevel> asks = new TreeMap<>();

    // Index for O(1) lookup of which side/price an order is at (for cancellation)
    private final Map<String, OrderLocationIndex> orderIndex = new HashMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    // =====================================================================
    // CORE OPERATIONS
    // =====================================================================

    /**
     * Add an order to the appropriate side of the book.
     * Creates a new PriceLevel if one doesn't exist at this price.
     */
    public void addOrder(Order order) {
        TreeMap<Double, PriceLevel> side = (order.getSide() == OrderSide.BUY) ? bids : asks;
        double price = order.getPrice();

        // Get or create price level
        PriceLevel level = side.computeIfAbsent(price, PriceLevel::new);
        level.addOrder(order);

        // Index for fast cancellation lookup
        orderIndex.put(order.getOrderId(),
                new OrderLocationIndex(order.getSide(), price));
    }

    /**
     * Remove an order by ID (for cancellation).
     *
     * WHY we need the orderIndex:
     * - Without it, we'd have to scan ALL price levels on BOTH sides to find the order → O(N*M).
     * - With the index, we know exactly which side and price level → O(log N) TreeMap lookup + O(K)
     *   scan within the price level (K = orders at that price, usually small).
     */
    public boolean removeOrder(String orderId) {
        OrderLocationIndex location = orderIndex.get(orderId);
        if (location == null) return false;

        TreeMap<Double, PriceLevel> side = (location.side == OrderSide.BUY) ? bids : asks;
        PriceLevel level = side.get(location.price);
        if (level == null) return false;

        boolean removed = level.removeOrder(orderId);
        if (removed) {
            orderIndex.remove(orderId);
            // Clean up empty price levels to keep the tree tidy
            if (level.isEmpty()) {
                side.remove(location.price);
            }
        }
        return removed;
    }

    // =====================================================================
    // BEST BID / ASK — Used by matching engine
    // =====================================================================

    /**
     * Best bid = highest buy price. This is what sellers match against.
     * Returns null if no bids in the book (no buyers).
     */
    public PriceLevel getBestBid() {
        if (bids.isEmpty()) return null;
        // firstEntry() on a reverse-sorted TreeMap gives the highest price
        return bids.firstEntry().getValue();
    }

    /**
     * Best ask = lowest sell price. This is what buyers match against.
     * Returns null if no asks in the book (no sellers).
     */
    public PriceLevel getBestAsk() {
        if (asks.isEmpty()) return null;
        // firstEntry() on a naturally-sorted TreeMap gives the lowest price
        return asks.firstEntry().getValue();
    }

    /**
     * Remove the best bid price level (used when completely consumed during matching).
     */
    public void removeBestBid() {
        if (!bids.isEmpty()) {
            Map.Entry<Double, PriceLevel> best = bids.firstEntry();
            PriceLevel level = best.getValue();
            // Remove all order index entries for orders at this level
            // (they should already be removed during matching, but clean up just in case)
            bids.pollFirstEntry();
        }
    }

    /**
     * Remove the best ask price level (used when completely consumed during matching).
     */
    public void removeBestAsk() {
        if (!asks.isEmpty()) {
            asks.pollFirstEntry();
        }
    }

    /**
     * Clean up empty price level if it exists.
     */
    public void cleanupPriceLevel(OrderSide side, double price) {
        TreeMap<Double, PriceLevel> book = (side == OrderSide.BUY) ? bids : asks;
        PriceLevel level = book.get(price);
        if (level != null && level.isEmpty()) {
            book.remove(price);
        }
    }

    // =====================================================================
    // DEPTH QUERIES — For display / market data
    // =====================================================================

    /**
     * Get top N bid levels for order book depth display.
     *
     * Example output (3 levels):
     *   2505.00 | 500  | 3 orders   ← best bid (highest)
     *   2504.50 | 200  | 1 order
     *   2504.00 | 1000 | 5 orders
     */
    public List<OrderBookEntry> getBidDepth(int levels) {
        List<OrderBookEntry> depth = new ArrayList<>();
        int count = 0;
        for (Map.Entry<Double, PriceLevel> entry : bids.entrySet()) {
            if (count >= levels) break;
            PriceLevel level = entry.getValue();
            depth.add(new OrderBookEntry(level.getPrice(), level.getTotalQuantity(), level.getOrderCount()));
            count++;
        }
        return depth;
    }

    /**
     * Get top N ask levels for order book depth display.
     *
     * Example output (3 levels):
     *   2506.00 | 300  | 2 orders   ← best ask (lowest)
     *   2506.50 | 150  | 1 order
     *   2507.00 | 800  | 4 orders
     */
    public List<OrderBookEntry> getAskDepth(int levels) {
        List<OrderBookEntry> depth = new ArrayList<>();
        int count = 0;
        for (Map.Entry<Double, PriceLevel> entry : asks.entrySet()) {
            if (count >= levels) break;
            PriceLevel level = entry.getValue();
            depth.add(new OrderBookEntry(level.getPrice(), level.getTotalQuantity(), level.getOrderCount()));
            count++;
        }
        return depth;
    }

    /**
     * Spread = best ask price - best bid price.
     * Narrow spread = liquid, competitive market. Wide spread = illiquid.
     * Returns -1 if either side is empty (no meaningful spread).
     */
    public double getSpread() {
        PriceLevel bestBid = getBestBid();
        PriceLevel bestAsk = getBestAsk();
        if (bestBid == null || bestAsk == null) return -1.0;
        return bestAsk.getPrice() - bestBid.getPrice();
    }

    /**
     * Total volume on the bid side (all price levels combined).
     */
    public int getTotalBidVolume() {
        return bids.values().stream().mapToInt(PriceLevel::getTotalQuantity).sum();
    }

    /**
     * Total volume on the ask side (all price levels combined).
     */
    public int getTotalAskVolume() {
        return asks.values().stream().mapToInt(PriceLevel::getTotalQuantity).sum();
    }

    public String getSymbol() { return symbol; }
    public boolean hasBids() { return !bids.isEmpty(); }
    public boolean hasAsks() { return !asks.isEmpty(); }

    @Override
    public String toString() {
        return String.format("OrderBook{%s, bids=%d levels, asks=%d levels, spread=%.2f}",
                symbol, bids.size(), asks.size(), getSpread());
    }

    // =====================================================================
    // INTERNAL — Order location index for O(1) cancellation lookup
    // =====================================================================

    /**
     * Tracks where an order lives in the book (which side and price level).
     * WHY: Without this, cancelling an order would require scanning all price levels.
     */
    private record OrderLocationIndex(OrderSide side, double price) {}
}
