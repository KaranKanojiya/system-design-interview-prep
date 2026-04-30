package com.systemdesign.ecommerce.exception;

/**
 * PaymentFailedException — Thrown when a payment gateway rejects the charge.
 *
 * In a real system the gateway would return an error code (card_declined,
 * insufficient_funds, etc.). Here we keep it simple with a message.
 */
public class PaymentFailedException extends ECommerceException {

    public PaymentFailedException(String message) {
        super(message);
    }

    public PaymentFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
