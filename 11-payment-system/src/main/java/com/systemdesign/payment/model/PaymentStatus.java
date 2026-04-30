package com.systemdesign.payment.model;

/**
 * PaymentStatus — Finite state machine for payment lifecycle.
 *
 * State transitions (the "happy path" arrow chain):
 *   INITIATED → PROCESSING → AUTHORIZED → CAPTURED → SETTLED
 *
 * From most states you can also transition to FAILED or CANCELLED.
 * CAPTURED/SETTLED can transition to REFUNDED.
 *
 * WHY a state machine?  In real payment systems (Stripe, Adyen, UPI),
 * a payment goes through multiple stages — each stage triggers different
 * side-effects (ledger entries, webhooks, fraud checks).  Guarding
 * transitions prevents double-charges and inconsistent ledger state.
 */
public enum PaymentStatus {

    /** Payment object created, not yet sent to processor. */
    INITIATED,

    /** Sent to processor, awaiting response (network call in flight). */
    PROCESSING,

    /** Processor approved — funds reserved on customer's instrument.
     *  For UPI/wallet this may be skipped (instant settlement). */
    AUTHORIZED,

    /** Merchant explicitly captured the authorized amount.
     *  Money will move in the next settlement cycle. */
    CAPTURED,

    /** Funds transferred to merchant account — terminal happy state. */
    SETTLED,

    /** Processor declined, or internal error — terminal sad state. */
    FAILED,

    /** A captured/settled payment was reversed back to the customer. */
    REFUNDED,

    /** Merchant or system cancelled before capture — terminal state. */
    CANCELLED
}
