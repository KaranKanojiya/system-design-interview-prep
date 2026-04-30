package com.systemdesign.ecommerce.exception;

/**
 * InsufficientStockException — Thrown when a reservation request exceeds
 * the available stock for a product.
 *
 * Carries the productId, requestedQty, and availableQty so the caller
 * (saga orchestrator) can log a precise diagnostic message and the
 * controller can return a user-friendly error.
 */
public class InsufficientStockException extends ECommerceException {

    private final String productId;
    private final int requested;
    private final int available;

    public InsufficientStockException(String productId, int requested, int available) {
        super(String.format("Insufficient stock for product '%s': requested=%d, available=%d",
                productId, requested, available));
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    public String getProductId() { return productId; }
    public int getRequested()    { return requested; }
    public int getAvailable()    { return available; }
}
