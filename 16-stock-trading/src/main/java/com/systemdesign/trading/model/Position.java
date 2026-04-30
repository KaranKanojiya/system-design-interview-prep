package com.systemdesign.trading.model;

/**
 * Position represents a user's holding in a specific stock.
 *
 * WHY quantity can be negative:
 * - Positive quantity = LONG position (user owns shares, profits when price goes up)
 * - Negative quantity = SHORT position (user owes shares, profits when price goes down)
 * - Zero quantity = FLAT (no exposure). Position may still exist for P&L history.
 *
 * KEY CALCULATIONS:
 * - Unrealized P&L: paper profit/loss based on current market price vs avg cost.
 *   Formula: (currentPrice - avgBuyPrice) * quantity
 *   For shorts: quantity is negative, so if price drops, P&L is positive (profit).
 * - Realized P&L: actual profit/loss from closed trades. Accumulated as trades close.
 * - investedValue: total capital deployed = avgBuyPrice * |quantity|
 * - currentValue: market value = currentPrice * |quantity|
 *
 * CALL CHAIN:
 * Trade executed → PortfolioService.updatePositionOnTrade() → Position.updateOnTrade() →
 * recalculates avgBuyPrice and quantity → MarketDataService price update →
 * Position.updateMarketPrice() → recalculates unrealized P&L
 */
public class Position {

    private final String userId;
    private final String symbol;
    private int quantity;           // Positive = long, negative = short, zero = flat
    private double avgBuyPrice;     // Volume-weighted average cost basis
    private double currentPrice;    // Last known market price
    private double investedValue;   // Capital deployed = avgBuyPrice * |quantity|
    private double currentValue;    // Market value = currentPrice * |quantity|
    private double realizedPnL;     // Accumulated realized profit/loss from closed trades

    public Position(String userId, String symbol) {
        this.userId = userId;
        this.symbol = symbol;
        this.quantity = 0;
        this.avgBuyPrice = 0.0;
        this.currentPrice = 0.0;
        this.investedValue = 0.0;
        this.currentValue = 0.0;
        this.realizedPnL = 0.0;
    }

    // =====================================================================
    // CORE METHODS
    // =====================================================================

    /**
     * Unrealized P&L: profit/loss if position were closed NOW at current market price.
     *
     * Example: Bought 100 shares at avg 2500, current price 2600
     *   Unrealized P&L = (2600 - 2500) * 100 = +10,000 (profit)
     *
     * For short positions (qty=-100, avg sell 2500, current 2400):
     *   Unrealized P&L = (2400 - 2500) * (-100) = +10,000 (profit on short)
     */
    public double getUnrealizedPnL() {
        if (quantity == 0) return 0.0;
        return (currentPrice - avgBuyPrice) * quantity;
    }

    /**
     * Realized P&L: actual profit/loss from trades that have been closed.
     * This accumulates over time as positions are opened and closed.
     */
    public double getRealizedPnL() {
        return realizedPnL;
    }

    /**
     * Update position when a trade executes.
     *
     * WHY weighted average for avgBuyPrice:
     * - If you buy 100 @ 2500, then 50 @ 2600:
     *   New avg = (100*2500 + 50*2600) / 150 = 2533.33
     * - This is the standard method for computing cost basis.
     *
     * WHY selling reduces position and may realize P&L:
     * - If holding 100 @ avg 2500, sell 50 @ 2700:
     *   Realized P&L = (2700 - 2500) * 50 = +10,000
     *   Remaining: 50 shares @ avg 2500 (avg doesn't change on sell)
     */
    public void updateOnTrade(Trade trade, boolean isBuyer) {
        int tradeQty = trade.getQuantity();
        double tradePrice = trade.getPrice();

        if (isBuyer) {
            // BUYING: increases position (or closes short)
            if (quantity >= 0) {
                // Adding to long position — recalculate weighted average
                double totalCost = (avgBuyPrice * quantity) + (tradePrice * tradeQty);
                quantity += tradeQty;
                avgBuyPrice = quantity > 0 ? totalCost / quantity : 0.0;
            } else {
                // Closing short position — realize P&L
                int closingQty = Math.min(tradeQty, Math.abs(quantity));
                realizedPnL += (avgBuyPrice - tradePrice) * closingQty;
                quantity += tradeQty;
                if (quantity > 0) {
                    // Flipped from short to long
                    avgBuyPrice = tradePrice;
                } else if (quantity == 0) {
                    avgBuyPrice = 0.0;
                }
                // If still short, avgBuyPrice stays the same
            }
        } else {
            // SELLING: decreases position (or opens short)
            if (quantity > 0) {
                // Closing long position — realize P&L
                int closingQty = Math.min(tradeQty, quantity);
                realizedPnL += (tradePrice - avgBuyPrice) * closingQty;
                quantity -= tradeQty;
                if (quantity < 0) {
                    // Flipped from long to short
                    avgBuyPrice = tradePrice;
                } else if (quantity == 0) {
                    avgBuyPrice = 0.0;
                }
                // If still long, avgBuyPrice stays the same
            } else {
                // Adding to short position — recalculate weighted average
                double totalCost = (avgBuyPrice * Math.abs(quantity)) + (tradePrice * tradeQty);
                quantity -= tradeQty;
                avgBuyPrice = quantity != 0 ? totalCost / Math.abs(quantity) : 0.0;
            }
        }

        // Update invested and current values
        recalculateValues();
    }

    /**
     * Update the position's current market price.
     * Called whenever MarketDataService receives a new price.
     */
    public void updateMarketPrice(double price) {
        this.currentPrice = price;
        recalculateValues();
    }

    private void recalculateValues() {
        this.investedValue = avgBuyPrice * Math.abs(quantity);
        this.currentValue = currentPrice * Math.abs(quantity);
    }

    // =====================================================================
    // GETTERS
    // =====================================================================

    public String getUserId() { return userId; }
    public String getSymbol() { return symbol; }
    public int getQuantity() { return quantity; }
    public double getAvgBuyPrice() { return avgBuyPrice; }
    public double getCurrentPrice() { return currentPrice; }
    public double getInvestedValue() { return investedValue; }
    public double getCurrentValue() { return currentValue; }

    @Override
    public String toString() {
        return String.format("Position{user='%s', symbol='%s', qty=%d, avg=%.2f, current=%.2f, " +
                        "unrealizedP&L=%.2f, realizedP&L=%.2f}",
                userId, symbol, quantity, avgBuyPrice, currentPrice,
                getUnrealizedPnL(), realizedPnL);
    }
}
