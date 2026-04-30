package com.systemdesign.payment.model;

/**
 * WebhookStatus — Delivery state of an outbound webhook event.
 *
 * PENDING   — queued, first delivery attempt not yet made
 * DELIVERED — merchant endpoint returned 2xx, done
 * FAILED    — latest attempt failed, will retry (attempts < maxAttempts)
 * EXHAUSTED — all retry attempts used, giving up
 *
 * WHY track webhook status separately?
 *   Webhooks are fire-and-forget from the payment flow's perspective,
 *   but merchants rely on them for order fulfillment.  We need to know
 *   which webhooks are stuck so we can retry or alert.
 */
public enum WebhookStatus {
    PENDING,
    DELIVERED,
    FAILED,
    EXHAUSTED
}
