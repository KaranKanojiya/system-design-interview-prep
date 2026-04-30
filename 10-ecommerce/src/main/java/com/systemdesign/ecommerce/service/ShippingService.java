package com.systemdesign.ecommerce.service;

import com.systemdesign.ecommerce.model.Order;
import com.systemdesign.ecommerce.model.Shipment;
import com.systemdesign.ecommerce.model.ShipmentStatus;
import com.systemdesign.ecommerce.strategy.shipping.ShippingStrategy;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ShippingService — Creates and tracks shipments.
 *
 * Interview notes:
 * - Shipments are stored in a local map (keyed by orderId) for simplicity.
 *   In production, this would be a separate Shipping microservice with its
 *   own database, integrating with carrier APIs (FedEx, UPS, USPS).
 * - The ShippingStrategy is injected per-call to allow different shipping
 *   speeds for different orders.
 *
 * Call chain: SagaOrchestrator → ShippingService → Shipment
 */
public class ShippingService {

    // Store shipments by orderId for quick lookup
    private final Map<String, Shipment> shipmentsByOrderId = new ConcurrentHashMap<>();
    private ShippingStrategy shippingStrategy;

    /**
     * Sets the shipping strategy for subsequent createShipment calls.
     */
    public void setShippingStrategy(ShippingStrategy shippingStrategy) {
        this.shippingStrategy = shippingStrategy;
    }

    /**
     * Creates a shipment for the given order.
     * Uses the current ShippingStrategy to determine estimated delivery.
     */
    public Shipment createShipment(Order order) {
        String shipmentId = "SHIP-" + UUID.randomUUID().toString().substring(0, 8);
        String trackingId = "TRACK-" + UUID.randomUUID().toString().substring(0, 8);

        int estimatedDays = (shippingStrategy != null) ? shippingStrategy.getEstimatedDays() : 7;
        LocalDateTime estimatedDelivery = LocalDateTime.now().plusDays(estimatedDays);

        Shipment shipment = new Shipment(
                shipmentId, order.getOrderId(), trackingId,
                "FastShip Logistics", estimatedDelivery);
        shipment.updateStatus(ShipmentStatus.SHIPPED);

        shipmentsByOrderId.put(order.getOrderId(), shipment);
        return shipment;
    }

    /**
     * Updates the status of a shipment (e.g., IN_TRANSIT → DELIVERED).
     */
    public void updateStatus(String shipmentId, ShipmentStatus newStatus) {
        shipmentsByOrderId.values().stream()
                .filter(s -> s.getShipmentId().equals(shipmentId))
                .findFirst()
                .ifPresent(s -> s.updateStatus(newStatus));
    }

    /**
     * Gets the shipment for a given order.
     */
    public Shipment getShipment(String orderId) {
        return shipmentsByOrderId.get(orderId);
    }
}
