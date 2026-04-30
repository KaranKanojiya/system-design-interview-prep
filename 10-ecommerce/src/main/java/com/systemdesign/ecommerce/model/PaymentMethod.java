package com.systemdesign.ecommerce.model;

/**
 * PaymentMethod — Supported payment methods.
 *
 * Interview notes:
 * - Each method maps to a PaymentStrategy implementation.
 * - COD (Cash On Delivery) always "succeeds" at order time but actual
 *   payment collection happens at delivery — the PaymentStatus stays
 *   PENDING until the driver confirms receipt.
 */
public enum PaymentMethod {
    CREDIT_CARD,
    DEBIT_CARD,
    WALLET,
    COD  // Cash On Delivery
}
