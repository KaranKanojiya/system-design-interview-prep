package com.systemdesign.ecommerce.model;

import java.time.LocalDateTime;

/**
 * Shipment — Tracks physical delivery of an order.
 *
 * Interview notes:
 * - One order → one shipment (simplified; real systems support split shipments).
 * - trackingId is the carrier-generated ID customers use to track on the carrier's site.
 * - estimatedDelivery is computed from the ShippingStrategy's getEstimatedDays().
 */
public class Shipment {

    private final String shipmentId;
    private final String orderId;
    private final String trackingId;
    private final String carrier;
    private ShipmentStatus status;
    private final LocalDateTime estimatedDelivery;
    private final LocalDateTime shippedAt;
    private LocalDateTime deliveredAt;

    public Shipment(String shipmentId, String orderId, String trackingId,
                    String carrier, LocalDateTime estimatedDelivery) {
        this.shipmentId = shipmentId;
        this.orderId = orderId;
        this.trackingId = trackingId;
        this.carrier = carrier;
        this.status = ShipmentStatus.PREPARING;
        this.estimatedDelivery = estimatedDelivery;
        this.shippedAt = LocalDateTime.now();
    }

    // ── Mutations ────────────────────────────────────────────────────────

    public void updateStatus(ShipmentStatus newStatus) {
        this.status = newStatus;
        if (newStatus == ShipmentStatus.DELIVERED) {
            this.deliveredAt = LocalDateTime.now();
        }
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getShipmentId()              { return shipmentId; }
    public String getOrderId()                 { return orderId; }
    public String getTrackingId()              { return trackingId; }
    public String getCarrier()                 { return carrier; }
    public ShipmentStatus getStatus()          { return status; }
    public LocalDateTime getEstimatedDelivery(){ return estimatedDelivery; }
    public LocalDateTime getShippedAt()        { return shippedAt; }
    public LocalDateTime getDeliveredAt()      { return deliveredAt; }

    @Override
    public String toString() {
        return String.format("Shipment{id='%s', orderId='%s', tracking='%s', carrier='%s', status=%s}",
                shipmentId, orderId, trackingId, carrier, status);
    }
}
