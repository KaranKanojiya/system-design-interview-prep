package com.systemdesign.trading.repository;

import com.systemdesign.trading.model.Stock;

import java.util.List;

/**
 * StockRepository stores and retrieves stock definitions.
 *
 * CALL CHAIN:
 * AppConfig seeds stocks → StockRepository.save() →
 * CircuitBreakerStrategy.check() → StockRepository.findBySymbol() → reads circuit limits
 */
public interface StockRepository {

    void save(Stock stock);

    Stock findBySymbol(String symbol);

    List<Stock> findAll();
}
