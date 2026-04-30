package com.systemdesign.ridesharing.exception;

/**
 * PaymentFailedException — Thrown when payment processing fails.
 *
 * Common causes:
 *   - Card declined (insufficient funds, expired card)
 *   - Wallet balance too low
 *   - Payment processor timeout (Stripe/Braintree down)
 *   - Fraud detection triggered
 *
 * In production:
 *   Payment failures don't block the ride completion. The rider is notified,
 *   the payment is queued for retry, and the amount is added to their
 *   outstanding balance. The rider can't request new rides until they
 *   settle the balance.
 */
public class PaymentFailedException extends RideException {

    public PaymentFailedException(String message) {
        super(message);
    }

    public PaymentFailedException(String paymentId, String reason) {
        super(String.format("Payment '%s' failed: %s", paymentId, reason));
    }
}
