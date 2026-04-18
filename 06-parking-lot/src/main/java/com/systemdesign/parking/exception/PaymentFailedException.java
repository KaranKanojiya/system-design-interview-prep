package com.systemdesign.parking.exception;

/**
 * Thrown when a payment is declined or fails to process.
 */
public class PaymentFailedException extends ParkingException {

    public PaymentFailedException(String message) {
        super(message);
    }
}
