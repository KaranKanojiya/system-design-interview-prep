package com.systemdesign.trading.strategy.risk;

import com.systemdesign.trading.model.*;

/**
 * PositionLimitStrategy checks if an order would exceed the maximum allowed position per symbol.
 *
 * WHY position limits exist:
 * - Prevent a single trader from cornering the market (accumulating disproportionate shares).
 * - SEBI (Indian regulator) sets position limits for futures/options.
 * - Brokers set limits to manage their own risk (if a client defaults, broker is liable).
 *
 * EXAMPLE:
 * - Max position = 10,000 shares per symbol.
 * - User holds 8,000 RELIANCE, places buy for 3,000.
 * - New position would be 11,000 > 10,000 → REJECTED.
 *
 * WHY this is separate from MarginCheck:
 * - A user might have enough money to buy 50,000 shares but be BLOCKED by position limits.
 * - Position limits are regulatory requirements; margin is financial.
 * - Different concerns → different strategies → can be composed independently.
 *
 * CALL CHAIN:
 * RiskService.runChecks() → PositionLimitStrategy.check() →
 * checks current position (tracked via currentPositionQuantity field) →
 * if new quantity would exceed maxPositionPerSymbol → reject
 */
public class PositionLimitStrategy implements RiskCheckStrategy {

    private final int maxPositionPerSymbol;

    // In a full implementation, this would query PositionRepository.
    // For simplicity, we accept the current position as context.
    // The TradingService passes this information when running risk checks.
    private int currentPositionQuantity;

    public PositionLimitStrategy(int maxPositionPerSymbol) {
        this.maxPositionPerSymbol = maxPositionPerSymbol;
        this.currentPositionQuantity = 0;
    }

    /**
     * Set the current position for the upcoming risk check.
     * Called by TradingService before running the risk chain.
     */
    public void setCurrentPositionQuantity(int quantity) {
        this.currentPositionQuantity = quantity;
    }

    @Override
    public RiskResult check(Order order, Account account, MarketData marketData) {
        int newPosition;

        if (order.getSide() == OrderSide.BUY) {
            // Buying increases position
            newPosition = currentPositionQuantity + order.getQuantity();
        } else {
            // Selling decreases position (but absolute value might increase for shorts)
            newPosition = currentPositionQuantity - order.getQuantity();
        }

        // Check absolute position (both long and short limits)
        if (Math.abs(newPosition) > maxPositionPerSymbol) {
            return RiskResult.reject(String.format(
                    "Position limit exceeded. Current: %d, Order: %s %d, New would be: %d, Max: %d",
                    currentPositionQuantity, order.getSide(), order.getQuantity(),
                    newPosition, maxPositionPerSymbol));
        }

        return RiskResult.pass();
    }
}
