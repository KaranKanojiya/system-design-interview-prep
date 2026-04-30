package com.systemdesign.ecommerce.service;

import com.systemdesign.ecommerce.exception.InsufficientStockException;
import com.systemdesign.ecommerce.model.Inventory;
import com.systemdesign.ecommerce.repository.InventoryRepository;

import java.util.List;

/**
 * InventoryService — Thread-safe inventory operations.
 *
 * Interview notes:
 * - All stock mutations delegate to Inventory model methods, which are
 *   synchronized. This service is the ONLY entry point for inventory changes
 *   — no other service directly touches Inventory objects.
 * - In a distributed system, you'd use Redis DECR for atomic stock
 *   reservation, or an optimistic lock (version column + retry) in a
 *   relational DB. The synchronized keyword here models the same semantic.
 *
 * Call chain: SagaOrchestrator → InventoryService → InventoryRepository → Inventory
 */
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Reserves stock for a product. Throws InsufficientStockException if
     * available stock is less than the requested quantity.
     *
     * Thread safety: Inventory.reserve() is synchronized, so concurrent
     * calls for the same product are serialized. This prevents overselling.
     */
    public void reserve(String productId, int quantity) {
        Inventory inventory = getInventoryOrThrow(productId);
        // This call is synchronized inside Inventory — safe under concurrency
        inventory.reserve(quantity);
        inventoryRepository.save(inventory);
    }

    /**
     * Confirms a reservation — stock is permanently consumed (shipped).
     */
    public void confirmReservation(String productId, int quantity) {
        Inventory inventory = getInventoryOrThrow(productId);
        inventory.confirmReservation(quantity);
        inventoryRepository.save(inventory);
    }

    /**
     * Releases a reservation — stock goes back to available pool.
     * Called by the saga compensator when payment fails.
     */
    public void releaseReservation(String productId, int quantity) {
        Inventory inventory = getInventoryOrThrow(productId);
        inventory.releaseReservation(quantity);
        inventoryRepository.save(inventory);
    }

    /**
     * Returns the Inventory for a product, or null if not found.
     */
    public Inventory getStock(String productId) {
        return inventoryRepository.findByProductId(productId).orElse(null);
    }

    /**
     * Returns all inventory records. Used by the stats display.
     */
    public List<Inventory> getAllInventory() {
        return inventoryRepository.findAll();
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private Inventory getInventoryOrThrow(String productId) {
        return inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InsufficientStockException(productId, 0, 0));
    }
}
