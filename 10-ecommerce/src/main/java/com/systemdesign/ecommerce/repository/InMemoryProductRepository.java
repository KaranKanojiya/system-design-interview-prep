package com.systemdesign.ecommerce.repository;

import com.systemdesign.ecommerce.model.Product;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryProductRepository — ConcurrentHashMap-backed product store.
 *
 * Thread-safe for concurrent reads; writes are atomic per key.
 */
public class InMemoryProductRepository implements ProductRepository {

    private final Map<String, Product> store = new ConcurrentHashMap<>();

    @Override
    public void save(Product product) {
        store.put(product.getId(), product);
    }

    @Override
    public Optional<Product> findById(String productId) {
        return Optional.ofNullable(store.get(productId));
    }

    @Override
    public List<Product> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public List<Product> findByName(String nameFragment) {
        String lowerFragment = nameFragment.toLowerCase();
        return store.values().stream()
                .filter(p -> p.getName().toLowerCase().contains(lowerFragment))
                .collect(Collectors.toList());
    }

    @Override
    public List<Product> findByCategory(String category) {
        return store.values().stream()
                .filter(p -> p.getCategory().equalsIgnoreCase(category))
                .collect(Collectors.toList());
    }

    @Override
    public void delete(String productId) {
        store.remove(productId);
    }
}
