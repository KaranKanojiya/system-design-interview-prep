package com.systemdesign.trading.strategy.pricing;

import com.systemdesign.trading.model.Position;
import com.systemdesign.trading.model.Trade;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * FIFOPnLStrategy calculates P&L using First-In-First-Out method.
 *
 * Used in India for tax calculation. The shares you bought FIRST are sold FIRST.
 *
 * ALGORITHM:
 * 1. Process trades in chronological order.
 * 2. Maintain a queue of buy "lots" (each lot = price + remaining qty).
 * 3. When a sell trade arrives, match against the OLDEST buy lot first.
 * 4. P&L for each match = (sellPrice - buyPrice) * matchedQty.
 * 5. Sum all P&L.
 *
 * WALK-THROUGH:
 *   Buy 100 @ 2500 → queue: [(2500, 100)]
 *   Buy 50 @ 2600  → queue: [(2500, 100), (2600, 50)]
 *   Sell 80 @ 2700  → match against FIRST lot:
 *     80 from (2500, 100) → P&L = (2700-2500)*80 = +16,000
 *     queue: [(2500, 20), (2600, 50)]
 *   Sell 30 @ 2650  → match against FIRST lot (20 remaining), then next:
 *     20 from (2500, 20) → P&L = (2650-2500)*20 = +3,000
 *     10 from (2600, 50) → P&L = (2650-2600)*10 = +500
 *     queue: [(2600, 40)]
 *   Total realized P&L = 16,000 + 3,000 + 500 = 19,500
 *
 * CALL CHAIN:
 * PortfolioService.calculatePnL() → FIFOPnLStrategy.calculatePnL() →
 * iterates trades chronologically → matches buys against sells in FIFO order
 */
public class FIFOPnLStrategy implements PnLStrategy {

    @Override
    public double calculatePnL(List<Trade> trades, Position position) {
        // Queue of buy lots: each entry is [price, remainingQty]
        Queue<double[]> buyLots = new LinkedList<>();
        double totalPnL = 0.0;

        // Process trades in order (assumed chronological)
        for (Trade trade : trades) {
            String userId = position.getUserId();

            // Determine if this user was the buyer or seller in this trade
            boolean isBuyer = trade.getBuyerUserId().equals(userId);

            if (isBuyer) {
                // This is a BUY — add to the queue as a new lot
                buyLots.add(new double[]{trade.getPrice(), trade.getQuantity()});
            } else {
                // This is a SELL — match against oldest buy lots (FIFO)
                int remainingToSell = trade.getQuantity();
                double sellPrice = trade.getPrice();

                while (remainingToSell > 0 && !buyLots.isEmpty()) {
                    double[] oldestLot = buyLots.peek();
                    double buyPrice = oldestLot[0];
                    int lotQty = (int) oldestLot[1];

                    int matchQty = Math.min(remainingToSell, lotQty);
                    totalPnL += (sellPrice - buyPrice) * matchQty;

                    remainingToSell -= matchQty;
                    oldestLot[1] -= matchQty;

                    if (oldestLot[1] <= 0) {
                        buyLots.poll(); // Lot fully consumed
                    }
                }
            }
        }

        return totalPnL;
    }
}
