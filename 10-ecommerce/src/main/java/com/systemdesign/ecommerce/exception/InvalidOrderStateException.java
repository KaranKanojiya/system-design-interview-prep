package com.systemdesign.ecommerce.exception;

/**
 * InvalidOrderStateException — Thrown when a state-machine transition
 * is attempted from an illegal "from" state.
 *
 * Example: trying to ship an order that hasn't been paid yet.
 */
public class InvalidOrderStateException extends ECommerceException {

    public InvalidOrderStateException(String message) {
        super(message);
    }
}
