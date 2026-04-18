package com.systemdesign.parking.model;

/**
 * Enum representing supported payment methods.
 * Each method is handled by a dedicated PaymentProcessor (Strategy pattern).
 */
public enum PaymentMethod {
    CASH("Cash"),
    CREDIT_CARD("Credit Card"),
    DEBIT_CARD("Debit Card");

    private final String displayName;

    PaymentMethod(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
