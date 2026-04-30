package com.systemdesign.trading.exception;

/**
 * Thrown when an order is rejected by risk checks.
 * The reason field contains the specific rejection cause.
 */
public class OrderRejectedException extends TradingException {

    private final String reason;

    public OrderRejectedException(String reason) {
        super("Order rejected: " + reason);
        this.reason = reason;
    }

    public String getReason() { return reason; }
}
