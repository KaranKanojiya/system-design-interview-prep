package com.systemdesign.trading.repository;

import com.systemdesign.trading.model.MarketData;

import java.util.List;

/**
 * MarketDataRepository stores and retrieves real-time market data.
 *
 * CALL CHAIN:
 * Trade executed → MarketDataService.updateOnTrade() →
 * MarketDataRepository.findBySymbol() → MarketData.updateOnTrade() →
 * MarketDataRepository.save() → display reads findBySymbol()
 */
public interface MarketDataRepository {

    void save(MarketData marketData);

    MarketData findBySymbol(String symbol);

    List<MarketData> findAll();
}
