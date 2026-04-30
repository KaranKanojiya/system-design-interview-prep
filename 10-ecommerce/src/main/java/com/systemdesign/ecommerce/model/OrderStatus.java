package com.systemdesign.ecommerce.model;

/**
 * OrderStatus — Finite state machine for order lifecycle.
 *
 * State transitions (guarded in Order methods):
 *
 *   CREATED ──► INVENTORY_RESERVED ──► PAYMENT_PENDING ──► PAYMENT_CONFIRMED ──► SHIPPED ──► DELIVERED
 *      │              │                      │                    │
 *      └──────────────┴──────────────────────┴────────────────────┴──► CANCELLED
 *                                                                        │
 *                                                                        ▼
 *                                                                     REFUNDED
 *
 * Interview notes:
 * - Each transition is validated in Order's state-machine methods so
 *   callers can't skip steps (e.g., ship before payment).
 * - CANCELLED is reachable from any pre-DELIVERED state.
 * - REFUNDED is only reachable from CANCELLED (payment was already taken).
 */
public enum OrderStatus {
    CREATED,
    INVENTORY_RESERVED,
    PAYMENT_PENDING,
    PAYMENT_CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    REFUNDED
}
