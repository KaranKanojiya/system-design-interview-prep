package com.systemdesign.trading.exception;

/**
 * TradingException is the base exception for all trading-related errors.
 *
 * WHY a custom exception hierarchy:
 * - Distinguishes trading errors from system errors (NullPointerException, etc.).
 * - Allows catching all trading errors at the controller level with one catch block.
 * - Subclasses carry specific context (margin amounts, order IDs, etc.).
 * - In production, these map to specific HTTP status codes and error response bodies.
 */
public class TradingException extends RuntimeException {

    public TradingException(String message) {
        super(message);
    }

    public TradingException(String message, Throwable cause) {
        super(message, cause);
    }
}
