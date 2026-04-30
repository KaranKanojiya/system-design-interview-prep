package com.systemdesign.trading.model;

/**
 * OrderType determines how/when an order executes.
 *
 * WHY these four types cover real-world trading:
 * - MARKET: Execute immediately at best available price. Used when speed > price.
 *   Risk: slippage in thin order books. Price field = 0 (irrelevant).
 * - LIMIT: Execute only at specified price or better. Most common order type.
 *   Buy limit at 100 means: buy at 100 or below. Sell limit at 100: sell at 100 or above.
 * - STOP_LOSS: Becomes a MARKET order when trigger price is hit. Used for loss protection.
 *   E.g., holding stock at 100, set stop-loss trigger at 95 → if price drops to 95, sell at market.
 * - STOP_LIMIT: Becomes a LIMIT order when trigger price is hit. More controlled than STOP_LOSS.
 *   Has both triggerPrice and price. When triggered, places limit order at specified price.
 *
 * CALL CHAIN:
 * User selects type → Order.Builder.type(OrderType) → MatchingEngine selects OrderExecutionStrategy
 * based on type → Strategy executes the appropriate matching logic
 */
public enum OrderType {
    MARKET,      // Execute immediately at best available price
    LIMIT,       // Execute at specified price or better
    STOP_LOSS,   // Becomes market order when trigger price is hit
    STOP_LIMIT   // Becomes limit order when trigger price is hit
}
