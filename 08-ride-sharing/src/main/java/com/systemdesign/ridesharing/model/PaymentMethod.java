package com.systemdesign.ridesharing.model;

/**
 * PaymentMethod — Supported payment methods.
 *
 * WHY an enum instead of String:
 *   Compile-time safety. A typo like "CREDIT_CARS" is caught at compile time,
 *   not at runtime when a rider is trying to pay for a ride.
 *
 * In production Uber/Lyft, payment methods map to payment processor adapters
 * (Stripe for cards, internal wallet service, cash handled by driver).
 */
public enum PaymentMethod {
    CREDIT_CARD,
    DEBIT_CARD,
    WALLET,
    CASH
}
