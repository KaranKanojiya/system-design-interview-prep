package com.systemdesign.trading.strategy.order;

import com.systemdesign.trading.engine.OrderBook;
import com.systemdesign.trading.model.Order;
import com.systemdesign.trading.model.Trade;

import java.util.List;

/**
 * OrderExecutionStrategy defines how different order types are matched.
 *
 * WHY Strategy pattern:
 * - MARKET and LIMIT orders have fundamentally different matching rules.
 * - Market orders sweep the book regardless of price → MarketOrderStrategy.
 * - Limit orders only match at or better than the limit price → LimitOrderStrategy.
 * - Adding new order types (e.g., Iceberg, Fill-or-Kill) means adding new strategies
 *   without modifying existing code → Open/Closed Principle.
 *
 * WHY not just if/else in MatchingEngine:
 * - Each strategy has ~50 lines of matching logic. Putting it all in one class
 *   would create a 200+ line god method.
 * - Strategies can be unit-tested independently.
 * - New strategies can be added without touching MatchingEngine.
 *
 * CALL CHAIN:
 * MatchingEngine.submitOrder() → looks up strategy by OrderType →
 * strategy.execute(order, book) → returns List<Trade>
 */
public interface OrderExecutionStrategy {

    /**
     * Execute an order against the given order book.
     *
     * @param order The incoming order to match
     * @param book  The order book for the order's symbol
     * @return List of trades generated (empty if no matches found)
     */
    List<Trade> execute(Order order, OrderBook book);
}
