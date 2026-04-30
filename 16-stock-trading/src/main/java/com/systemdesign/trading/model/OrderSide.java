package com.systemdesign.trading.model;

/**
 * OrderSide represents the direction of a trade.
 *
 * WHY an enum (not String):
 * - Type safety: prevents typos like "Buy" vs "BUY" vs "buy"
 * - Used in matching logic: BUY orders match against ASK side of order book,
 *   SELL orders match against BID side. This mapping is critical for correctness.
 *
 * CALL CHAIN:
 * TradingController parses side → Order.Builder.side(OrderSide) → MatchingEngine checks side
 * to decide which side of the book to match against
 */
public enum OrderSide {
    BUY,   // Buyer wants to purchase shares — matches against asks (sell side)
    SELL   // Seller wants to sell shares — matches against bids (buy side)
}
