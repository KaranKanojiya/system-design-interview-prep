package com.systemdesign.trading.strategy.pricing;

import com.systemdesign.trading.model.Position;
import com.systemdesign.trading.model.Trade;

import java.util.List;

/**
 * PnLStrategy defines how profit and loss is calculated from trades.
 *
 * WHY multiple P&L methods:
 * - Different jurisdictions use different methods for tax calculation.
 * - India (FIFO): First shares bought are first shares sold. Required by tax law.
 * - US (Average Cost): Some brokers use weighted average cost basis for simplicity.
 * - The choice affects reported P&L and tax liability.
 *
 * EXAMPLE (same trades, different P&L):
 *   Buy 100 @ 2500, Buy 50 @ 2600, Sell 80 @ 2700.
 *
 *   FIFO: First 80 sold are from the first buy at 2500.
 *         P&L = (2700 - 2500) * 80 = +16,000
 *
 *   Average Cost: Avg = (100*2500 + 50*2600) / 150 = 2533.33
 *                 P&L = (2700 - 2533.33) * 80 = +13,333
 *
 * CALL CHAIN:
 * PortfolioService.calculatePnL() → PnLStrategy.calculatePnL() →
 * reads trades from TradeRepository → computes P&L based on method
 */
public interface PnLStrategy {

    /**
     * Calculate realized P&L from a list of trades for a given position.
     *
     * @param trades   All trades for this user+symbol, ordered by timestamp
     * @param position The current position (for context)
     * @return The calculated realized P&L
     */
    double calculatePnL(List<Trade> trades, Position position);
}
