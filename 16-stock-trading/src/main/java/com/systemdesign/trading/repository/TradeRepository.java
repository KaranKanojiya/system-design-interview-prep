package com.systemdesign.trading.repository;

import com.systemdesign.trading.model.Trade;

import java.util.List;

/**
 * TradeRepository stores and retrieves executed trades.
 *
 * WHY separate from OrderRepository:
 * - One order can produce multiple trades (partial fills).
 * - Trades are immutable historical records; orders are mutable (status changes).
 * - Trade queries are different: "all trades for RELIANCE today" vs "all my open orders".
 *
 * CALL CHAIN:
 * MatchingEngine creates Trade → TradingService → TradeRepository.save() →
 * PortfolioService.calculatePnL() → TradeRepository.findByUserIdAndSymbol() →
 * SettlementService → TradeRepository.findUnsettled()
 */
public interface TradeRepository {

    void save(Trade trade);

    Trade findById(String tradeId);

    List<Trade> findBySymbol(String symbol);

    List<Trade> findByUserId(String userId);

    List<Trade> findByUserIdAndSymbol(String userId, String symbol);

    List<Trade> findAll();

    List<Trade> findUnsettled();

    void markSettled(String tradeId);
}
