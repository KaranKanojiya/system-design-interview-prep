package com.systemdesign.trading.repository;

import com.systemdesign.trading.model.Position;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryPositionRepository stores user positions.
 *
 * WHY composite key (userId + symbol):
 * - A user can hold positions in multiple stocks.
 * - The natural key is (userId, symbol) — one position per user per stock.
 * - We create a composite string key "userId:symbol" for HashMap lookup.
 */
public class InMemoryPositionRepository implements PositionRepository {

    private final Map<String, Position> positions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userIndex = new ConcurrentHashMap<>();

    private String compositeKey(String userId, String symbol) {
        return userId + ":" + symbol;
    }

    @Override
    public void save(Position position) {
        String key = compositeKey(position.getUserId(), position.getSymbol());
        positions.put(key, position);
        userIndex.computeIfAbsent(position.getUserId(), k -> ConcurrentHashMap.newKeySet())
                .add(key);
    }

    @Override
    public Position findByUserIdAndSymbol(String userId, String symbol) {
        return positions.get(compositeKey(userId, symbol));
    }

    @Override
    public List<Position> findByUserId(String userId) {
        Set<String> keys = userIndex.getOrDefault(userId, Collections.emptySet());
        return keys.stream()
                .map(positions::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<Position> findAll() {
        return new ArrayList<>(positions.values());
    }
}
