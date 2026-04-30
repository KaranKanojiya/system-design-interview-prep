package com.systemdesign.trading.service;

import com.systemdesign.trading.model.Position;
import com.systemdesign.trading.model.Trade;
import com.systemdesign.trading.repository.PositionRepository;
import com.systemdesign.trading.repository.TradeRepository;
import com.systemdesign.trading.strategy.pricing.PnLStrategy;

import java.util.List;

/**
 * PortfolioService manages user positions and P&L calculations.
 *
 * RESPONSIBILITIES:
 * 1. Track positions: quantity and avg cost for each user+symbol.
 * 2. Update positions on trade execution.
 * 3. Calculate unrealized P&L (based on current market price).
 * 4. Calculate realized P&L (using configurable PnLStrategy: FIFO or AvgCost).
 *
 * WHY PnLStrategy is pluggable:
 * - India uses FIFO for tax; US brokers often use average cost.
 * - By injecting the strategy, the same PortfolioService works for both.
 *
 * CALL CHAIN:
 * Trade executed → TradingService → PortfolioService.updatePositionOnTrade() →
 * updates Position object → saves to PositionRepository →
 * User views portfolio → PortfolioService.getPositions() → reads from repository →
 * calculatePnL() → PnLStrategy computes realized P&L from trade history
 */
public class PortfolioService {

    private final PositionRepository positionRepository;
    private final TradeRepository tradeRepository;
    private final PnLStrategy pnlStrategy;

    public PortfolioService(PositionRepository positionRepository,
                            TradeRepository tradeRepository,
                            PnLStrategy pnlStrategy) {
        this.positionRepository = positionRepository;
        this.tradeRepository = tradeRepository;
        this.pnlStrategy = pnlStrategy;
    }

    /**
     * Get all positions for a user.
     */
    public List<Position> getPositions(String userId) {
        return positionRepository.findByUserId(userId);
    }

    /**
     * Get a specific position for a user and symbol.
     * Returns null if no position exists.
     */
    public Position getPosition(String userId, String symbol) {
        return positionRepository.findByUserIdAndSymbol(userId, symbol);
    }

    /**
     * Update position when a trade executes.
     *
     * WHY we update BOTH buyer and seller:
     * - A trade has two sides. Buyer's position increases, seller's decreases.
     * - Both positions must be updated atomically (in production, within a DB transaction).
     */
    public void updatePositionOnTrade(Trade trade) {
        // Update buyer's position
        updateSinglePosition(trade.getBuyerUserId(), trade.getSymbol(), trade, true);

        // Update seller's position
        updateSinglePosition(trade.getSellerUserId(), trade.getSymbol(), trade, false);
    }

    private void updateSinglePosition(String userId, String symbol, Trade trade, boolean isBuyer) {
        Position position = positionRepository.findByUserIdAndSymbol(userId, symbol);
        if (position == null) {
            position = new Position(userId, symbol);
        }
        position.updateOnTrade(trade, isBuyer);
        position.updateMarketPrice(trade.getPrice()); // Use trade price as latest
        positionRepository.save(position);
    }

    /**
     * Calculate realized P&L for a user+symbol using the configured PnLStrategy.
     */
    public double calculatePnL(String userId, String symbol) {
        Position position = positionRepository.findByUserIdAndSymbol(userId, symbol);
        if (position == null) return 0.0;

        List<Trade> trades = tradeRepository.findByUserIdAndSymbol(userId, symbol);
        return pnlStrategy.calculatePnL(trades, position);
    }

    /**
     * Calculate total P&L across all positions for a user.
     */
    public double calculateTotalPnL(String userId) {
        List<Position> positions = positionRepository.findByUserId(userId);
        double total = 0.0;
        for (Position pos : positions) {
            total += pos.getUnrealizedPnL() + pos.getRealizedPnL();
        }
        return total;
    }

    /**
     * Update market price for a position (for unrealized P&L calculation).
     */
    public void updateMarketPrice(String userId, String symbol, double price) {
        Position position = positionRepository.findByUserIdAndSymbol(userId, symbol);
        if (position != null) {
            position.updateMarketPrice(price);
        }
    }
}
