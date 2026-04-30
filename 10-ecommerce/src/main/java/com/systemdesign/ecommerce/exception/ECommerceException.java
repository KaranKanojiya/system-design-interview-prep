package com.systemdesign.ecommerce.exception;

/**
 * ECommerceException — Base exception for the e-commerce domain.
 *
 * All domain-specific exceptions extend this so callers can catch a
 * single type for generic error handling while still pattern-matching
 * on subtypes (InsufficientStockException, PaymentFailedException, etc.)
 * when they need specific recovery logic.
 */
public class ECommerceException extends RuntimeException {

    public ECommerceException(String message) {
        super(message);
    }

    public ECommerceException(String message, Throwable cause) {
        super(message, cause);
    }
}
