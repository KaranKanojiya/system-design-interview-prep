package com.systemdesign.ecommerce.service;

import com.systemdesign.ecommerce.model.Product;
import com.systemdesign.ecommerce.repository.ProductRepository;

import java.util.List;
import java.util.Optional;

/**
 * ProductService — CRUD and search operations for the product catalog.
 *
 * Interview notes:
 * - Thin service layer: validates inputs, delegates to repository.
 * - In production, search-by-name would hit an Elasticsearch cluster,
 *   not iterate over all products in memory. But the interface stays
 *   the same — only the repository implementation changes.
 *
 * Call chain: Controller → ProductService → ProductRepository
 */
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────

    public void addProduct(Product product) {
        productRepository.save(product);
    }

    public Optional<Product> getProduct(String productId) {
        return productRepository.findById(productId);
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public void deleteProduct(String productId) {
        productRepository.delete(productId);
    }

    // ── Search ───────────────────────────────────────────────────────────

    public List<Product> searchByName(String nameFragment) {
        return productRepository.findByName(nameFragment);
    }

    public List<Product> searchByCategory(String category) {
        return productRepository.findByCategory(category);
    }
}
