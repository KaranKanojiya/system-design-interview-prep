package com.systemdesign.trading.repository;

import com.systemdesign.trading.model.Trade;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryTradeRepository stores executed trades.
 *
 * WHY track settled vs unsettled:
 * - In India, equity trades settle T+1 (trade today, settlement tomorrow).
 * - Unsettled trades need fund/share transfer during settlement window.
 * - The settledTradeIds set tracks which trades have been settled.
 */
public class InMemoryTradeRepository implements TradeRepository {

    private final Map<String, Trade> trades = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> symbolIndex = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userIndex = new ConcurrentHashMap<>();
    private final Set<String> settledTradeIds = ConcurrentHashMap.newKeySet();

    @Override
    public void save(Trade trade) {
        trades.put(trade.getTradeId(), trade);

        // Index by symbol
        symbolIndex.computeIfAbsent(trade.getSymbol(), k -> ConcurrentHashMap.newKeySet())
                .add(trade.getTradeId());

        // Index by both buyer and seller user IDs
        userIndex.computeIfAbsent(trade.getBuyerUserId(), k -> ConcurrentHashMap.newKeySet())
                .add(trade.getTradeId());
        userIndex.computeIfAbsent(trade.getSellerUserId(), k -> ConcurrentHashMap.newKeySet())
                .add(trade.getTradeId());
    }

    @Override
    public Trade findById(String tradeId) {
        return trades.get(tradeId);
    }

    @Override
    public List<Trade> findBySymbol(String symbol) {
        Set<String> tradeIds = symbolIndex.getOrDefault(symbol, Collections.emptySet());
        return tradeIds.stream().map(trades::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public List<Trade> findByUserId(String userId) {
        Set<String> tradeIds = userIndex.getOrDefault(userId, Collections.emptySet());
        return tradeIds.stream().map(trades::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public List<Trade> findByUserIdAndSymbol(String userId, String symbol) {
        Set<String> userTradeIds = userIndex.getOrDefault(userId, Collections.emptySet());
        Set<String> symbolTradeIds = symbolIndex.getOrDefault(symbol, Collections.emptySet());

        // Intersection of user's trades and symbol's trades
        return userTradeIds.stream()
                .filter(symbolTradeIds::contains)
                .map(trades::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Trade::getTimestamp))
                .collect(Collectors.toList());
    }

    @Override
    public List<Trade> findAll() {
        return new ArrayList<>(trades.values());
    }

    @Override
    public List<Trade> findUnsettled() {
        return trades.values().stream()
                .filter(t -> !settledTradeIds.contains(t.getTradeId()))
                .collect(Collectors.toList());
    }

    @Override
    public void markSettled(String tradeId) {
        settledTradeIds.add(tradeId);
    }
}
