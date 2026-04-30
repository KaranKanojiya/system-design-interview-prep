package com.systemdesign.payment.exception;

/**
 * DuplicatePaymentException — Thrown when an idempotency key has already been used.
 *
 * This is NOT really an error — it means the client retried and we should
 * return the cached result.  The PaymentService catches this and returns
 * the original payment instead of processing again.
 */
public class DuplicatePaymentException extends PaymentException {

    private final String idempotencyKey;
    private final String existingPaymentId;

    public DuplicatePaymentException(String idempotencyKey, String existingPaymentId) {
        super("Duplicate payment detected for idempotency key: " + idempotencyKey
              + ", existing payment: " + existingPaymentId);
        this.idempotencyKey = idempotencyKey;
        this.existingPaymentId = existingPaymentId;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getExistingPaymentId() { return existingPaymentId; }
}
