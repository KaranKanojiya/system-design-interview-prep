package com.systemdesign.trading.repository;

import com.systemdesign.trading.model.Stock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryStockRepository stores stock definitions.
 * Keyed by symbol (e.g., "RELIANCE", "TCS").
 */
public class InMemoryStockRepository implements StockRepository {

    private final Map<String, Stock> stocks = new ConcurrentHashMap<>();

    @Override
    public void save(Stock stock) {
        stocks.put(stock.getSymbol(), stock);
    }

    @Override
    public Stock findBySymbol(String symbol) {
        return stocks.get(symbol);
    }

    @Override
    public List<Stock> findAll() {
        return new ArrayList<>(stocks.values());
    }
}
