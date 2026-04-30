package com.systemdesign.trading.exception;

/**
 * Thrown when an order violates circuit breaker limits or trading is halted.
 */
public class CircuitBreakerException extends TradingException {

    public CircuitBreakerException(String message) {
        super("Circuit breaker: " + message);
    }
}
