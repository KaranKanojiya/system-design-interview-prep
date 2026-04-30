package com.systemdesign.payment.model;

/**
 * PaymentMethod — The instrument the customer uses to pay.
 *
 * Each method has different characteristics that affect processing:
 *   - CREDIT_CARD / DEBIT_CARD: two-phase (authorize → capture), ~200ms latency
 *   - UPI: single-phase instant settlement, very popular in India
 *   - WALLET: pre-funded, fastest, lowest failure rate
 *   - BANK_TRANSFER: slowest, used for large B2B payments
 *
 * WHY an enum instead of a String?
 *   Compile-time safety — the Strategy pattern selects the right
 *   PaymentProcessor based on this enum, so typos are caught early.
 */
public enum PaymentMethod {
    CREDIT_CARD,
    DEBIT_CARD,
    UPI,
    WALLET,
    BANK_TRANSFER
}
