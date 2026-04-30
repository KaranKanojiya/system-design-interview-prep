package com.systemdesign.trading.model;

import java.time.Instant;

/**
 * MarketData holds real-time pricing and volume data for a stock.
 *
 * WHY these specific fields (they map to what a real trading terminal shows):
 * - ltp (Last Traded Price): the most recent execution price. THE key number.
 * - bidPrice/askPrice: best bid (highest buy) and best ask (lowest sell) from order book.
 * - bidQty/askQty: quantity available at best bid/ask. Shows liquidity.
 * - OHLC: Open-High-Low-Close for the current trading day. Used for charting.
 * - volume: total shares traded today. Indicates activity/interest.
 * - changePercent: how much the stock moved from previous close. Shows momentum.
 *
 * CALL CHAIN:
 * Trade executed → MarketDataService.updateOnTrade(symbol, price, qty) →
 * MarketData.updateOnTrade() → updates ltp, high/low, volume →
 * PortfolioService reads ltp to calculate unrealized P&L
 */
public class MarketData {

    private final String symbol;
    private double ltp;            // Last Traded Price — most recent execution price
    private double bidPrice;       // Best bid (highest buy price in order book)
    private double askPrice;       // Best ask (lowest sell price in order book)
    private int bidQty;            // Quantity at best bid
    private int askQty;            // Quantity at best ask
    private double open;           // Day's opening price
    private double high;           // Day's highest traded price
    private double low;            // Day's lowest traded price
    private double close;          // Previous day's close (for change% calculation)
    private long volume;           // Total shares traded today
    private double changePercent;  // (ltp - close) / close * 100
    private Instant timestamp;     // When this data was last updated

    public MarketData(String symbol, double openPrice) {
        this.symbol = symbol;
        this.ltp = openPrice;
        this.bidPrice = 0.0;
        this.askPrice = 0.0;
        this.bidQty = 0;
        this.askQty = 0;
        this.open = openPrice;
        this.high = openPrice;
        this.low = openPrice;
        this.close = openPrice;  // Previous close = open for first day
        this.volume = 0;
        this.changePercent = 0.0;
        this.timestamp = Instant.now();
    }

    /**
     * Update market data when a trade executes.
     *
     * WHY update high/low/volume here:
     * - Each trade potentially sets a new high or low for the day.
     * - Volume accumulates with every trade.
     * - changePercent recalculated relative to previous close.
     */
    public void updateOnTrade(double price, int qty) {
        this.ltp = price;
        this.high = Math.max(this.high, price);
        this.low = Math.min(this.low, price);
        this.volume += qty;
        this.changePercent = close > 0 ? ((price - close) / close) * 100.0 : 0.0;
        this.timestamp = Instant.now();
    }

    /**
     * Update bid/ask from order book state.
     * Called after any order book change (new order, cancellation, fill).
     */
    public void updateBidAsk(double bidPrice, int bidQty, double askPrice, int askQty) {
        this.bidPrice = bidPrice;
        this.bidQty = bidQty;
        this.askPrice = askPrice;
        this.askQty = askQty;
        this.timestamp = Instant.now();
    }

    // --- Getters ---

    public String getSymbol() { return symbol; }
    public double getLtp() { return ltp; }
    public double getBidPrice() { return bidPrice; }
    public double getAskPrice() { return askPrice; }
    public int getBidQty() { return bidQty; }
    public int getAskQty() { return askQty; }
    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getClose() { return close; }
    public long getVolume() { return volume; }
    public double getChangePercent() { return changePercent; }
    public Instant getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("MarketData{%s, LTP=%.2f, Bid=%.2f(%d), Ask=%.2f(%d), " +
                        "O=%.2f H=%.2f L=%.2f C=%.2f, Vol=%d, Chg=%.2f%%}",
                symbol, ltp, bidPrice, bidQty, askPrice, askQty,
                open, high, low, close, volume, changePercent);
    }
}
