package com.systemdesign.payment.model;

import java.time.LocalDateTime;

/**
 * IdempotencyRecord — Cached result of a previous payment request.
 *
 * IDEMPOTENCY IN PAYMENT SYSTEMS:
 *   When a client retries a payment (e.g. network timeout, they didn't
 *   get the response), we must NOT charge them twice.  The client sends
 *   an idempotency key (usually a UUID they generate).  If we've seen
 *   that key before, we return the cached result instead of processing again.
 *
 * EXPIRATION:
 *   Records expire after 24 hours.  After that, the same key can be reused.
 *   This is consistent with Stripe's idempotency key behavior.
 *
 * RACE CONDITION:
 *   Two threads with the same key arriving simultaneously could both pass
 *   the "key not found" check and both process the payment.  The
 *   IdempotencyService uses synchronized to prevent this.
 */
public class IdempotencyRecord {

    private final String key;
    private final String paymentId;
    private final PaymentStatus responseStatus;
    private final String responseBody;   // JSON-like string of the cached response
    private final LocalDateTime createdAt;
    private final LocalDateTime expiresAt;

    public IdempotencyRecord(String key, String paymentId, PaymentStatus responseStatus,
                             String responseBody) {
        this.key = key;
        this.paymentId = paymentId;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = createdAt.plusHours(24); // 24-hour TTL, same as Stripe
    }

    /**
     * Check if this record has expired.
     * Expired records should be cleaned up by IdempotencyService.cleanup().
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    // ── Getters ──
    public String getKey() { return key; }
    public String getPaymentId() { return paymentId; }
    public PaymentStatus getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }

    @Override
    public String toString() {
        return "IdempotencyRecord{" +
                "key='" + key + '\'' +
                ", paymentId='" + paymentId + '\'' +
                ", status=" + responseStatus +
                ", expired=" + isExpired() +
                '}';
    }
}
