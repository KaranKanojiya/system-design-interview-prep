package com.systemdesign.trading.repository;

import com.systemdesign.trading.model.MarketData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryMarketDataRepository stores real-time market data.
 * Keyed by symbol. Each symbol has exactly one MarketData entry (updated in place).
 */
public class InMemoryMarketDataRepository implements MarketDataRepository {

    private final Map<String, MarketData> marketDataMap = new ConcurrentHashMap<>();

    @Override
    public void save(MarketData marketData) {
        marketDataMap.put(marketData.getSymbol(), marketData);
    }

    @Override
    public MarketData findBySymbol(String symbol) {
        return marketDataMap.get(symbol);
    }

    @Override
    public List<MarketData> findAll() {
        return new ArrayList<>(marketDataMap.values());
    }
}
