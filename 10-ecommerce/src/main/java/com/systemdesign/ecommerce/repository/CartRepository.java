package com.systemdesign.ecommerce.repository;

import com.systemdesign.ecommerce.model.Cart;

import java.util.Optional;

/**
 * CartRepository — Data access abstraction for shopping carts.
 *
 * Interview notes:
 * - Keyed by userId (one active cart per user).
 * - In production, carts are typically stored in Redis (fast, TTL for
 *   abandoned carts) rather than the primary database.
 */
public interface CartRepository {

    void save(Cart cart);

    Optional<Cart> findByUserId(String userId);

    void deleteByUserId(String userId);
}
