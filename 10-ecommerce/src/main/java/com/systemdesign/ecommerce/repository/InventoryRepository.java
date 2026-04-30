package com.systemdesign.ecommerce.repository;

import com.systemdesign.ecommerce.model.Inventory;

import java.util.List;
import java.util.Optional;

/**
 * InventoryRepository — Data access abstraction for inventory/stock levels.
 *
 * Interview notes:
 * - In production, inventory is often in a separate microservice with its
 *   own database, accessed via gRPC or an internal API.
 * - Stock queries must be strongly consistent (not eventually consistent)
 *   to avoid overselling — hence synchronized methods in Inventory.
 */
public interface InventoryRepository {

    void save(Inventory inventory);

    Optional<Inventory> findByProductId(String productId);

    List<Inventory> findAll();
}
