package com.systemdesign.trading.service;

import com.systemdesign.trading.exception.InvalidOrderException;
import com.systemdesign.trading.model.*;
import com.systemdesign.trading.repository.OrderRepository;
import com.systemdesign.trading.repository.StockRepository;

import java.util.List;

/**
 * OrderService validates order fields, stores orders, and manages order lifecycle.
 *
 * VALIDATION RULES:
 * - Symbol must exist in StockRepository.
 * - Quantity must be positive and a multiple of lotSize.
 * - Price must be positive for limit orders, zero for market orders.
 * - Trigger price required for stop-loss orders.
 *
 * WHY separate from TradingService:
 * - TradingService orchestrates the full flow (risk → match → settle).
 * - OrderService focuses on order CRUD and validation.
 * - Single Responsibility: validation logic doesn't belong in the orchestrator.
 *
 * CALL CHAIN:
 * TradingService.placeOrder() → OrderService.validateAndSave(order) →
 * validates fields → saves to OrderRepository → returns validated order →
 * TradingService proceeds with risk checks and matching
 */
public class OrderService {

    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;

    public OrderService(OrderRepository orderRepository, StockRepository stockRepository) {
        this.orderRepository = orderRepository;
        this.stockRepository = stockRepository;
    }

    /**
     * Validate order fields and save to repository.
     *
     * @throws InvalidOrderException if any validation fails
     */
    public Order validateAndSave(Order order) {
        validateOrder(order);
        orderRepository.save(order);
        return order;
    }

    /**
     * Validate order fields.
     */
    private void validateOrder(Order order) {
        if (order.getSymbol() == null || order.getSymbol().isBlank()) {
            throw new InvalidOrderException("Symbol is required");
        }

        Stock stock = stockRepository.findBySymbol(order.getSymbol());
        if (stock == null) {
            throw new InvalidOrderException("Unknown symbol: " + order.getSymbol());
        }

        if (order.getQuantity() <= 0) {
            throw new InvalidOrderException("Quantity must be positive");
        }

        // Check lot size: quantity must be a multiple of the stock's lot size
        if (stock.getLotSize() > 1 && order.getQuantity() % stock.getLotSize() != 0) {
            throw new InvalidOrderException(String.format(
                    "Quantity %d is not a multiple of lot size %d for %s",
                    order.getQuantity(), stock.getLotSize(), order.getSymbol()));
        }

        // Limit orders must have a positive price
        if (order.getType() == OrderType.LIMIT && order.getPrice() <= 0) {
            throw new InvalidOrderException("Limit order must have a positive price");
        }

        // Stop-loss orders must have a trigger price
        if ((order.getType() == OrderType.STOP_LOSS || order.getType() == OrderType.STOP_LIMIT)
                && order.getTriggerPrice() <= 0) {
            throw new InvalidOrderException("Stop-loss order must have a trigger price");
        }
    }

    /**
     * Update an order in the repository (after fill, cancel, etc.).
     */
    public void updateOrder(Order order) {
        orderRepository.save(order);
    }

    /**
     * Get an order by ID.
     */
    public Order getOrder(String orderId) {
        return orderRepository.findById(orderId);
    }

    /**
     * Get all orders for a user.
     */
    public List<Order> getOrdersByUserId(String userId) {
        return orderRepository.findByUserId(userId);
    }

    /**
     * Get all orders for a symbol.
     */
    public List<Order> getOrdersBySymbol(String symbol) {
        return orderRepository.findBySymbol(symbol);
    }

    /**
     * Get all orders.
     */
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }
}
