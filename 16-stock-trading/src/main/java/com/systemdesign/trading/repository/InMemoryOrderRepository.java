package com.systemdesign.trading.repository;

import com.systemdesign.trading.model.Order;
import com.systemdesign.trading.model.OrderStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryOrderRepository stores orders in a ConcurrentHashMap.
 *
 * WHY ConcurrentHashMap:
 * - Multiple threads (matching engines for different symbols) may save orders concurrently.
 * - ConcurrentHashMap provides thread-safe reads/writes without explicit synchronization.
 * - In production, this would be a database with proper indexing on userId, symbol, status.
 *
 * WHY secondary indexes (userIdIndex, symbolIndex):
 * - Primary key lookup (orderId) is O(1) via HashMap.
 * - But queries like "all orders for user123" would require full scan without an index.
 * - We maintain manual indexes as Map<String, Set<String>> for O(1) lookup.
 * - In production, the database handles this via SQL indexes.
 */
public class InMemoryOrderRepository implements OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userIdIndex = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> symbolIndex = new ConcurrentHashMap<>();

    @Override
    public void save(Order order) {
        orders.put(order.getOrderId(), order);

        // Maintain secondary indexes
        userIdIndex.computeIfAbsent(order.getUserId(), k -> ConcurrentHashMap.newKeySet())
                .add(order.getOrderId());
        symbolIndex.computeIfAbsent(order.getSymbol(), k -> ConcurrentHashMap.newKeySet())
                .add(order.getOrderId());
    }

    @Override
    public Order findById(String orderId) {
        return orders.get(orderId);
    }

    @Override
    public List<Order> findByUserId(String userId) {
        Set<String> orderIds = userIdIndex.getOrDefault(userId, Collections.emptySet());
        return orderIds.stream()
                .map(orders::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<Order> findBySymbol(String symbol) {
        Set<String> orderIds = symbolIndex.getOrDefault(symbol, Collections.emptySet());
        return orderIds.stream()
                .map(orders::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<Order> findAll() {
        return new ArrayList<>(orders.values());
    }

    @Override
    public List<Order> findUnsettledOrders() {
        return orders.values().stream()
                .filter(o -> o.getStatus() == OrderStatus.FILLED)
                .collect(Collectors.toList());
    }
}
