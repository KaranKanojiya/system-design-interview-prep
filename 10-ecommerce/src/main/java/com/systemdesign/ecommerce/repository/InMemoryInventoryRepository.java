package com.systemdesign.ecommerce.repository;

import com.systemdesign.ecommerce.model.Inventory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryInventoryRepository — ConcurrentHashMap-backed inventory store.
 */
public class InMemoryInventoryRepository implements InventoryRepository {

    private final Map<String, Inventory> store = new ConcurrentHashMap<>();

    @Override
    public void save(Inventory inventory) {
        store.put(inventory.getProductId(), inventory);
    }

    @Override
    public Optional<Inventory> findByProductId(String productId) {
        return Optional.ofNullable(store.get(productId));
    }

    @Override
    public List<Inventory> findAll() {
        return List.copyOf(store.values());
    }
}
