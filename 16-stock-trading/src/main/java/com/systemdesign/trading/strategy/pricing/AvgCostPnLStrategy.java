package com.systemdesign.trading.strategy.pricing;

import com.systemdesign.trading.model.Position;
import com.systemdesign.trading.model.Trade;

import java.util.List;

/**
 * AvgCostPnLStrategy calculates P&L using the average cost basis method.
 *
 * ALGORITHM:
 * - Maintain a running average cost of all buys.
 * - When selling: P&L = (sellPrice - avgCost) * sellQty.
 * - Simpler than FIFO but can give different results.
 *
 * WALK-THROUGH:
 *   Buy 100 @ 2500 → avgCost = 2500, totalQty = 100
 *   Buy 50 @ 2600  → avgCost = (100*2500 + 50*2600) / 150 = 2533.33, totalQty = 150
 *   Sell 80 @ 2700  → P&L = (2700 - 2533.33) * 80 = +13,333.33
 *     totalQty = 70, avgCost stays at 2533.33
 *
 * Compare with FIFO which gave +16,000 for the same trade:
 * - FIFO attributed the sell to the cheapest buy lot (2500), so higher profit.
 * - Avg cost "spreads" the cost across all buys, so lower per-unit profit.
 *
 * CALL CHAIN:
 * PortfolioService.calculatePnL() → AvgCostPnLStrategy.calculatePnL() →
 * iterates trades → maintains running average → calculates P&L on sells
 */
public class AvgCostPnLStrategy implements PnLStrategy {

    @Override
    public double calculatePnL(List<Trade> trades, Position position) {
        double avgCost = 0.0;
        int totalQty = 0;
        double totalPnL = 0.0;

        for (Trade trade : trades) {
            String userId = position.getUserId();
            boolean isBuyer = trade.getBuyerUserId().equals(userId);

            if (isBuyer) {
                // BUY: recalculate weighted average cost
                double totalCost = (avgCost * totalQty) + (trade.getPrice() * trade.getQuantity());
                totalQty += trade.getQuantity();
                avgCost = totalQty > 0 ? totalCost / totalQty : 0.0;
            } else {
                // SELL: P&L based on average cost
                int sellQty = Math.min(trade.getQuantity(), totalQty);
                totalPnL += (trade.getPrice() - avgCost) * sellQty;
                totalQty -= sellQty;
                // avgCost doesn't change on sell (cost basis of remaining shares is the same)
                if (totalQty == 0) avgCost = 0.0;
            }
        }

        return totalPnL;
    }
}
