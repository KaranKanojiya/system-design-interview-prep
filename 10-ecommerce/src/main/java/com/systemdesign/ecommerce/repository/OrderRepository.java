package com.systemdesign.ecommerce.repository;

import com.systemdesign.ecommerce.model.Order;

import java.util.List;
import java.util.Optional;

/**
 * OrderRepository — Data access abstraction for orders.
 *
 * Interview notes:
 * - findByUserId is a critical query: "show me my orders" is one of the
 *   most common reads in e-commerce. In production you'd have a GSI
 *   (Global Secondary Index) on userId in DynamoDB, or a denormalized
 *   user-orders table.
 */
public interface OrderRepository {

    void save(Order order);

    Optional<Order> findById(String orderId);

    List<Order> findByUserId(String userId);

    List<Order> findAll();
}
