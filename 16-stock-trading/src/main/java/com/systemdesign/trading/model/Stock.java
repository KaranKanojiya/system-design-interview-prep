package com.systemdesign.trading.model;

/**
 * Stock represents a tradable security on an exchange.
 *
 * WHY these fields:
 * - symbol: The unique ticker (e.g., "RELIANCE") used as the key across the entire system
 *   (order books, market data, positions). Must be immutable once created.
 * - lotSize: In India, some derivatives trade in lots (e.g., NIFTY lot = 50). For equities, default = 1.
 * - tickSize: Minimum price movement (NSE uses 0.05 for most stocks). Ensures prices snap to valid levels.
 * - upperCircuitLimit / lowerCircuitLimit: Price bands set by the exchange to prevent extreme volatility.
 *   Orders outside these bands are REJECTED by the CircuitBreakerStrategy.
 * - isTradingHalted: Exchange can halt trading during extreme events (e.g., index circuit breaker).
 *
 * CALL CHAIN:
 * AppConfig seeds stocks → StockRepository stores them → CircuitBreakerStrategy reads limits →
 * MatchingEngine uses symbol to route to correct OrderBook
 */
public class Stock {

    private final String symbol;       // e.g., "RELIANCE", "TCS", "INFY"
    private final String name;         // e.g., "Reliance Industries Ltd"
    private final String exchange;     // "NSE" or "BSE"
    private final int lotSize;         // Minimum tradable quantity (default 1 for equities)
    private final double tickSize;     // Minimum price increment (0.05 for most NSE stocks)
    private double upperCircuitLimit;  // Max allowed price for the day
    private double lowerCircuitLimit;  // Min allowed price for the day
    private boolean isTradingHalted;   // True if exchange has halted trading

    public Stock(String symbol, String name, String exchange, int lotSize, double tickSize,
                 double upperCircuitLimit, double lowerCircuitLimit) {
        this.symbol = symbol;
        this.name = name;
        this.exchange = exchange;
        this.lotSize = lotSize;
        this.tickSize = tickSize;
        this.upperCircuitLimit = upperCircuitLimit;
        this.lowerCircuitLimit = lowerCircuitLimit;
        this.isTradingHalted = false;
    }

    // --- Getters ---

    public String getSymbol() { return symbol; }
    public String getName() { return name; }
    public String getExchange() { return exchange; }
    public int getLotSize() { return lotSize; }
    public double getTickSize() { return tickSize; }
    public double getUpperCircuitLimit() { return upperCircuitLimit; }
    public double getLowerCircuitLimit() { return lowerCircuitLimit; }
    public boolean isTradingHalted() { return isTradingHalted; }

    // --- Setters for mutable fields ---

    public void setUpperCircuitLimit(double upperCircuitLimit) {
        this.upperCircuitLimit = upperCircuitLimit;
    }

    public void setLowerCircuitLimit(double lowerCircuitLimit) {
        this.lowerCircuitLimit = lowerCircuitLimit;
    }

    public void setTradingHalted(boolean tradingHalted) {
        this.isTradingHalted = tradingHalted;
    }

    @Override
    public String toString() {
        return String.format("Stock{symbol='%s', name='%s', exchange='%s', circuit=[%.2f - %.2f], halted=%s}",
                symbol, name, exchange, lowerCircuitLimit, upperCircuitLimit, isTradingHalted);
    }
}
