package com.systemdesign.trading.exception;

/**
 * Thrown when a user's available margin is insufficient for the requested order.
 *
 * Contains both available and required amounts for clear error messaging.
 */
public class InsufficientMarginException extends TradingException {

    private final double available;
    private final double required;

    public InsufficientMarginException(double available, double required) {
        super(String.format("Insufficient margin. Available: %.2f, Required: %.2f", available, required));
        this.available = available;
        this.required = required;
    }

    public double getAvailable() { return available; }
    public double getRequired() { return required; }
}
