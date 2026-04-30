package com.systemdesign.trading.repository;

import com.systemdesign.trading.model.Order;

import java.util.List;

/**
 * OrderRepository stores and retrieves orders.
 *
 * WHY an interface:
 * - Decouples service layer from storage implementation.
 * - InMemoryOrderRepository for testing/demo, JdbcOrderRepository for production.
 * - Services only depend on this interface (Dependency Inversion Principle).
 *
 * CALL CHAIN:
 * OrderService.createOrder() → OrderRepository.save() →
 * TradingService reads → OrderRepository.findByUserId() →
 * Matching updates → OrderRepository.save() (update)
 */
public interface OrderRepository {

    void save(Order order);

    Order findById(String orderId);

    List<Order> findByUserId(String userId);

    List<Order> findBySymbol(String symbol);

    List<Order> findAll();

    /**
     * Find all unsettled (filled but not yet settled) orders.
     */
    List<Order> findUnsettledOrders();
}
