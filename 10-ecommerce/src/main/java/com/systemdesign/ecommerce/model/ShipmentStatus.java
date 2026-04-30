package com.systemdesign.ecommerce.model;

/**
 * ShipmentStatus — Tracking lifecycle of a shipment.
 *
 *   PREPARING → SHIPPED → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED
 */
public enum ShipmentStatus {
    PREPARING,
    SHIPPED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED
}
