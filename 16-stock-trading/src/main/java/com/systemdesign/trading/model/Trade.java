package com.systemdesign.trading.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Trade represents a completed transaction between a buyer and seller.
 *
 * WHY Trade is immutable:
 * - A trade is a historical fact — once executed, it cannot change.
 * - Immutability simplifies settlement, auditing, and P&L calculation.
 * - Each trade is a result of matching a buy order against a sell order at a specific price/qty.
 *
 * HOW trades are created:
 * - MatchingEngine finds overlapping buy/sell orders → creates Trade with:
 *   price = the passive order's price (maker's price, not taker's)
 *   quantity = min(buyRemaining, sellRemaining)
 * - One order can produce MULTIPLE trades (partial fills against different counterparties).
 *
 * CALL CHAIN:
 * MatchingEngine.submitOrder() creates Trade → TradingService processes it →
 * PortfolioService.updatePositionOnTrade() → AccountService.settleTradePayment() →
 * NotificationService notifies both parties
 */
public class Trade {

    private final String tradeId;       // UUID — globally unique trade identifier
    private final String buyOrderId;    // The buy order that was (partially) filled
    private final String sellOrderId;   // The sell order that was (partially) filled
    private final String buyerUserId;   // Buyer's user ID — for settlement
    private final String sellerUserId;  // Seller's user ID — for settlement
    private final String symbol;        // Which stock was traded
    private final double price;         // Execution price (passive order's price)
    private final int quantity;         // Number of shares traded
    private final Instant timestamp;    // When the trade happened

    public Trade(String buyOrderId, String sellOrderId, String buyerUserId,
                 String sellerUserId, String symbol, double price, int quantity) {
        this.tradeId = UUID.randomUUID().toString().substring(0, 8);
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.buyerUserId = buyerUserId;
        this.sellerUserId = sellerUserId;
        this.symbol = symbol;
        this.price = price;
        this.quantity = quantity;
        this.timestamp = Instant.now();
    }

    // --- Getters (no setters — immutable) ---

    public String getTradeId() { return tradeId; }
    public String getBuyOrderId() { return buyOrderId; }
    public String getSellOrderId() { return sellOrderId; }
    public String getBuyerUserId() { return buyerUserId; }
    public String getSellerUserId() { return sellerUserId; }
    public String getSymbol() { return symbol; }
    public double getPrice() { return price; }
    public int getQuantity() { return quantity; }
    public Instant getTimestamp() { return timestamp; }

    /**
     * Total value of this trade = price * quantity.
     * Used by settlement to determine fund transfer amount.
     */
    public double getValue() {
        return price * quantity;
    }

    @Override
    public String toString() {
        return String.format("Trade{id='%s', %s: %s bought %d @ %.2f from %s, value=%.2f}",
                tradeId, symbol, buyerUserId, quantity, price, sellerUserId, getValue());
    }
}
