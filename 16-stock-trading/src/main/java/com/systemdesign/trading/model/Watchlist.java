package com.systemdesign.trading.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Watchlist allows a user to track a curated set of stock symbols.
 *
 * WHY watchlists matter for trading:
 * - Users typically trade 5-20 stocks regularly out of thousands available.
 * - Watchlists drive the market data subscription model: only stream prices for watched symbols.
 * - In production, watchlist changes trigger WebSocket subscribe/unsubscribe events.
 *
 * CALL CHAIN:
 * User creates watchlist → adds symbols → MarketDataService.subscribeToSymbol() for each →
 * price updates streamed only for watched symbols → displayed on trading terminal
 */
public class Watchlist {

    private final String watchlistId;
    private final String userId;
    private String name;
    private final List<String> symbols;

    public Watchlist(String userId, String name) {
        this.watchlistId = "WL-" + UUID.randomUUID().toString().substring(0, 8);
        this.userId = userId;
        this.name = name;
        this.symbols = new ArrayList<>();
    }

    /**
     * Add a symbol to the watchlist.
     * WHY check for duplicates: a user shouldn't see "RELIANCE" twice in their watchlist.
     */
    public boolean addSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        String upper = symbol.toUpperCase();
        if (symbols.contains(upper)) return false;
        symbols.add(upper);
        return true;
    }

    /**
     * Remove a symbol from the watchlist.
     */
    public boolean removeSymbol(String symbol) {
        if (symbol == null) return false;
        return symbols.remove(symbol.toUpperCase());
    }

    public String getWatchlistId() { return watchlistId; }
    public String getUserId() { return userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<String> getSymbols() { return new ArrayList<>(symbols); }

    @Override
    public String toString() {
        return String.format("Watchlist{id='%s', user='%s', name='%s', symbols=%s}",
                watchlistId, userId, name, symbols);
    }
}
