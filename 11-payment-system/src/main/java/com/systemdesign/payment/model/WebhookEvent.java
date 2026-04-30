package com.systemdesign.payment.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * WebhookEvent — An outbound notification to a merchant's webhook endpoint.
 *
 * WEBHOOK LIFECYCLE:
 *   1. Payment completes → WebhookEvent created (PENDING)
 *   2. WebhookService POSTs payload to merchant's webhookUrl
 *   3. If merchant returns 2xx → DELIVERED (done)
 *   4. If merchant returns non-2xx or times out → FAILED, schedule retry
 *   5. Retry with exponential backoff: 1s, 2s, 4s, 8s, 16s
 *   6. After maxAttempts (5) failures → EXHAUSTED (give up, alert ops)
 *
 * WHY exponential backoff?
 *   If the merchant's server is down, hammering it every second makes things
 *   worse.  Exponential backoff gives it time to recover while still
 *   guaranteeing eventual delivery.
 *
 * HMAC-SHA256 signature:
 *   We sign the payload with the merchant's API key so they can verify
 *   the webhook came from us and wasn't tampered with.  Stripe does this too.
 */
public class WebhookEvent {

    private final String eventId;
    private final String eventType;       // e.g. "payment.succeeded", "refund.completed"
    private final String paymentId;
    private final String merchantId;
    private final String payload;         // JSON-like string
    private WebhookStatus status;
    private int attempts;
    private final int maxAttempts;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime nextRetryAt;
    private final LocalDateTime createdAt;

    public WebhookEvent(String eventType, String paymentId, String merchantId, String payload) {
        this.eventId = "EVT-" + UUID.randomUUID().toString().substring(0, 8);
        this.eventType = eventType;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.payload = payload;
        this.status = WebhookStatus.PENDING;
        this.attempts = 0;
        this.maxAttempts = 5;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Record a delivery attempt.
     * Called by WebhookService after each POST (success or failure).
     */
    public void recordAttempt(boolean success) {
        this.attempts++;
        this.lastAttemptAt = LocalDateTime.now();

        if (success) {
            this.status = WebhookStatus.DELIVERED;
            this.nextRetryAt = null;
        } else if (attempts >= maxAttempts) {
            // All retries exhausted — give up
            this.status = WebhookStatus.EXHAUSTED;
            this.nextRetryAt = null;
        } else {
            // Schedule next retry with exponential backoff: 2^(attempts-1) seconds
            this.status = WebhookStatus.FAILED;
            long backoffSeconds = (long) Math.pow(2, attempts - 1); // 1, 2, 4, 8, 16
            this.nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds);
        }
    }

    // ── Getters ──
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPaymentId() { return paymentId; }
    public String getMerchantId() { return merchantId; }
    public String getPayload() { return payload; }
    public WebhookStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public LocalDateTime getLastAttemptAt() { return lastAttemptAt; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "WebhookEvent{" +
                "id='" + eventId + '\'' +
                ", type='" + eventType + '\'' +
                ", paymentId='" + paymentId + '\'' +
                ", status=" + status +
                ", attempts=" + attempts + "/" + maxAttempts +
                '}';
    }
}
