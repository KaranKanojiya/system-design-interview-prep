package com.systemdesign.trading.model;

/**
 * OrderStatus tracks the lifecycle state of an order.
 *
 * STATE MACHINE (valid transitions):
 *   PENDING_RISK → OPEN (risk checks passed)
 *   PENDING_RISK → REJECTED (risk check failed — margin, circuit breaker, etc.)
 *   OPEN → PARTIALLY_FILLED (some quantity matched)
 *   OPEN → FILLED (all quantity matched)
 *   OPEN → CANCELLED (user cancelled)
 *   PARTIALLY_FILLED → FILLED (remaining quantity matched)
 *   PARTIALLY_FILLED → CANCELLED (user cancelled remaining)
 *
 * WHY PENDING_RISK exists:
 * - Orders go through risk checks BEFORE entering the matching engine.
 * - This state makes the lifecycle explicit and auditable.
 * - In production, risk checks may be async (margin check against clearing house).
 *
 * CALL CHAIN:
 * Order created as PENDING_RISK → RiskService checks → if pass: set OPEN → MatchingEngine matches →
 * PARTIALLY_FILLED or FILLED → SettlementService settles FILLED orders
 */
public enum OrderStatus {
    PENDING_RISK,      // Awaiting risk/margin checks
    OPEN,              // Risk passed, sitting in order book waiting for match
    PARTIALLY_FILLED,  // Some quantity filled, rest still in order book
    FILLED,            // Fully executed — all quantity matched
    CANCELLED,         // User cancelled (or system cancelled remaining after partial fill)
    REJECTED           // Risk check failed (insufficient margin, circuit breaker, etc.)
}
