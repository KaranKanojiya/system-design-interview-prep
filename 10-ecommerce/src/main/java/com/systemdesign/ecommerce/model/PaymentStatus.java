package com.systemdesign.ecommerce.model;

/**
 * PaymentStatus — Lifecycle of a payment attempt.
 *
 *   PENDING → PROCESSING → COMPLETED
 *                        → FAILED
 *   COMPLETED → REFUNDED
 */
public enum PaymentStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    REFUNDED
}
