package com.systemdesign.parking.model;

/**
 * Enum representing the lifecycle status of a payment transaction.
 */
public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED;

    @Override
    public String toString() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
