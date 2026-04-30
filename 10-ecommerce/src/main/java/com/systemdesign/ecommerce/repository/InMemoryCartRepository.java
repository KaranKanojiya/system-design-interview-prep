package com.systemdesign.ecommerce.repository;

import com.systemdesign.ecommerce.model.Cart;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryCartRepository — ConcurrentHashMap-backed cart store.
 * Key is userId (one cart per user).
 */
public class InMemoryCartRepository implements CartRepository {

    private final Map<String, Cart> store = new ConcurrentHashMap<>();

    @Override
    public void save(Cart cart) {
        store.put(cart.getUserId(), cart);
    }

    @Override
    public Optional<Cart> findByUserId(String userId) {
        return Optional.ofNullable(store.get(userId));
    }

    @Override
    public void deleteByUserId(String userId) {
        store.remove(userId);
    }
}
