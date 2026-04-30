package com.systemdesign.ecommerce.repository;

import com.systemdesign.ecommerce.model.Product;

import java.util.List;
import java.util.Optional;

/**
 * ProductRepository — Data access abstraction for the product catalog.
 *
 * Interview notes:
 * - Repository pattern isolates persistence details from the service layer.
 * - In production: backed by a NoSQL store (DynamoDB) or search index
 *   (Elasticsearch) for fast catalog queries.
 * - Here: InMemoryProductRepository uses a ConcurrentHashMap.
 */
public interface ProductRepository {

    void save(Product product);

    Optional<Product> findById(String productId);

    List<Product> findAll();

    List<Product> findByName(String nameFragment);

    List<Product> findByCategory(String category);

    void delete(String productId);
}
