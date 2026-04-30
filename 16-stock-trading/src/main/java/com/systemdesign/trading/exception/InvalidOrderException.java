package com.systemdesign.trading.exception;

/**
 * Thrown when an order has invalid fields (zero quantity, negative price, unknown symbol, etc.).
 */
public class InvalidOrderException extends TradingException {

    public InvalidOrderException(String message) {
        super("Invalid order: " + message);
    }
}
