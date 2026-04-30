package com.systemdesign.trading.service;

import com.systemdesign.trading.engine.MatchingEngine;
import com.systemdesign.trading.engine.OrderBook;
import com.systemdesign.trading.model.Order;
import com.systemdesign.trading.model.Trade;
import com.systemdesign.trading.repository.TradeRepository;

import java.util.List;

/**
 * MatchingService wraps the MatchingEngine and handles trade persistence.
 *
 * WHY a service wrapper around the engine:
 * - MatchingEngine is a pure algorithm: it matches orders and returns trades.
 * - MatchingService adds infrastructure concerns: saving trades to repository,
 *   logging, metrics, and routing orders to the correct engine.
 * - This separation keeps the engine testable without repository dependencies.
 *
 * CALL CHAIN:
 * TradingService.placeOrder() → MatchingService.submitOrder() →
 * MatchingEngine.submitOrder() → returns List<Trade> →
 * MatchingService saves each trade to TradeRepository →
 * returns trades to TradingService for further processing
 */
public class MatchingService {

    private final MatchingEngine matchingEngine;
    private final TradeRepository tradeRepository;

    public MatchingService(MatchingEngine matchingEngine, TradeRepository tradeRepository) {
        this.matchingEngine = matchingEngine;
        this.tradeRepository = tradeRepository;
    }

    /**
     * Submit an order for matching and persist resulting trades.
     */
    public List<Trade> submitOrder(Order order) {
        // Delegate to the matching engine
        List<Trade> trades = matchingEngine.submitOrder(order);

        // Persist all generated trades
        for (Trade trade : trades) {
            tradeRepository.save(trade);
        }

        return trades;
    }

    /**
     * Cancel an order by removing it from the matching engine's order book.
     */
    public boolean cancelOrder(String orderId, String symbol) {
        return matchingEngine.cancelOrder(orderId, symbol);
    }

    /**
     * Get the order book for a symbol (for display/market data).
     */
    public OrderBook getOrderBook(String symbol) {
        return matchingEngine.getOrderBook(symbol);
    }

    /**
     * Get the underlying matching engine (for direct access in demos).
     */
    public MatchingEngine getMatchingEngine() {
        return matchingEngine;
    }
}
