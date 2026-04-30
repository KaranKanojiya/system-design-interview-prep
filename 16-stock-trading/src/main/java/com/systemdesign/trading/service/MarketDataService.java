package com.systemdesign.trading.service;

import com.systemdesign.trading.engine.OrderBook;
import com.systemdesign.trading.engine.PriceLevel;
import com.systemdesign.trading.model.MarketData;
import com.systemdesign.trading.repository.MarketDataRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MarketDataService manages real-time market data for all traded symbols.
 *
 * RESPONSIBILITIES:
 * 1. Update market data when trades execute (price, volume, high/low).
 * 2. Update bid/ask from order book state after any book change.
 * 3. Track symbol subscriptions (which symbols are being watched).
 * 4. Provide OHLCV (Open-High-Low-Close-Volume) data for charting.
 *
 * WHY market data is separate from order book:
 * - Order book is internal engine state (actual orders with user IDs).
 * - Market data is public information (prices, volumes — no user details).
 * - Different update frequencies: order book changes on every order; market data
 *   changes only on trades and book-top changes.
 *
 * CALL CHAIN:
 * Trade executed → TradingService → MarketDataService.updateOnTrade() → updates LTP, H/L, volume →
 * MarketDataService.updateBidAsk() → reads best bid/ask from OrderBook →
 * display/clients read getMarketData()
 */
public class MarketDataService {

    private final MarketDataRepository marketDataRepository;
    private final Set<String> subscribedSymbols = new HashSet<>();

    public MarketDataService(MarketDataRepository marketDataRepository) {
        this.marketDataRepository = marketDataRepository;
    }

    /**
     * Get current market data for a symbol.
     */
    public MarketData getMarketData(String symbol) {
        return marketDataRepository.findBySymbol(symbol);
    }

    /**
     * Update market data after a trade executes.
     * Updates: LTP, high, low, volume, change%.
     */
    public void updateOnTrade(String symbol, double price, int quantity) {
        MarketData data = marketDataRepository.findBySymbol(symbol);
        if (data != null) {
            data.updateOnTrade(price, quantity);
        }
    }

    /**
     * Update bid/ask prices from the order book.
     * Called after any order book modification (new order, fill, cancel).
     */
    public void updateBidAsk(String symbol, OrderBook book) {
        MarketData data = marketDataRepository.findBySymbol(symbol);
        if (data == null || book == null) return;

        PriceLevel bestBid = book.getBestBid();
        PriceLevel bestAsk = book.getBestAsk();

        data.updateBidAsk(
                bestBid != null ? bestBid.getPrice() : 0.0,
                bestBid != null ? bestBid.getTotalQuantity() : 0,
                bestAsk != null ? bestAsk.getPrice() : 0.0,
                bestAsk != null ? bestAsk.getTotalQuantity() : 0
        );
    }

    /**
     * Subscribe to market data for a symbol.
     * In production, this would open a WebSocket channel or add to a multicast group.
     */
    public void subscribeToSymbol(String symbol) {
        subscribedSymbols.add(symbol);
    }

    /**
     * Get OHLCV data for a symbol (just returns current MarketData which has OHLCV).
     */
    public MarketData getOHLCV(String symbol) {
        return marketDataRepository.findBySymbol(symbol);
    }

    /**
     * Get all market data entries.
     */
    public List<MarketData> getAllMarketData() {
        return marketDataRepository.findAll();
    }

    /**
     * Initialize market data for a symbol.
     */
    public void initializeMarketData(MarketData data) {
        marketDataRepository.save(data);
    }
}
