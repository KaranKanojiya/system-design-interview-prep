package com.systemdesign.payment.exception;

/**
 * PaymentException — Base exception for all payment system errors.
 *
 * WHY a custom exception hierarchy?
 *   Different errors need different handling:
 *   - DuplicatePaymentException → return cached result (not an error)
 *   - InsufficientFundsException → decline, don't retry
 *   - FraudDetectedException → block, alert fraud team
 *   - WebhookDeliveryException → retry with backoff
 *
 *   A catch(PaymentException e) catches them all, but callers can
 *   also catch specific subtypes for fine-grained handling.
 */
public class PaymentException extends RuntimeException {

    public PaymentException(String message) {
        super(message);
    }

    public PaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
