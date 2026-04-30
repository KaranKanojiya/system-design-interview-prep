package com.systemdesign.ecommerce.exception;

/**
 * OrderNotFoundException — Thrown when an orderId lookup finds no match.
 */
public class OrderNotFoundException extends ECommerceException {

    public OrderNotFoundException(String orderId) {
        super("Order not found: " + orderId);
    }
}
