package com.systemdesign.payment.service;

import com.systemdesign.payment.model.Merchant;
import com.systemdesign.payment.model.Payment;
import com.systemdesign.payment.model.WebhookEvent;
import com.systemdesign.payment.model.WebhookStatus;
import com.systemdesign.payment.repository.MerchantRepository;
import com.systemdesign.payment.repository.WebhookRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/**
 * WebhookService — Delivers event notifications to merchant endpoints.
 *
 * WEBHOOK PATTERN:
 *   Instead of merchants polling us ("did my payment succeed?"), we
 *   push events to them.  This is the standard pattern used by
 *   Stripe, PayPal, Adyen, Razorpay, etc.
 *
 * DELIVERY GUARANTEES:
 *   - At-least-once delivery (not exactly-once — merchants must be idempotent)
 *   - Retry with exponential backoff: 1s, 2s, 4s, 8s, 16s
 *   - Max 5 attempts, then EXHAUSTED
 *   - Payload signed with HMAC-SHA256 using merchant's API key
 *
 * HMAC SIGNATURE:
 *   We compute HMAC-SHA256(payload, merchantApiKey) and send it in a header.
 *   The merchant verifies the signature to ensure:
 *     1. The webhook came from us (authenticity)
 *     2. The payload wasn't tampered with (integrity)
 *   This prevents attackers from sending fake webhooks to the merchant.
 *
 * CALL CHAIN:
 *   PaymentService.processPayment() → WebhookService.dispatchWebhook(eventType, payment)
 *     → create WebhookEvent
 *     → simulate POST to merchant URL
 *     → if fails, schedule retry with backoff
 *
 * SIMULATED DELIVERY:
 *   We don't make real HTTP calls.  Instead we simulate success/failure
 *   with a configurable failure rate for demo purposes.
 */
public class WebhookService {

    private final WebhookRepository webhookRepository;
    private final MerchantRepository merchantRepository;
    private final Random random = new Random();

    // Configurable failure simulation for demos
    private boolean simulateFirstAttemptFailure = false;

    public WebhookService(WebhookRepository webhookRepository,
                          MerchantRepository merchantRepository) {
        this.webhookRepository = webhookRepository;
        this.merchantRepository = merchantRepository;
    }

    /**
     * Set whether to simulate first-attempt failure (for retry demo).
     */
    public void setSimulateFirstAttemptFailure(boolean simulate) {
        this.simulateFirstAttemptFailure = simulate;
    }

    /**
     * Dispatch a webhook event for a payment.
     *
     * @param eventType e.g. "payment.succeeded", "payment.failed", "refund.completed"
     * @param payment the payment this event is about
     * @return the created WebhookEvent
     */
    public WebhookEvent dispatchWebhook(String eventType, Payment payment) {
        // Build a JSON-like payload
        String payload = buildPayload(eventType, payment);

        // Create the webhook event
        WebhookEvent event = new WebhookEvent(
            eventType,
            payment.getPaymentId(),
            payment.getMerchantId(),
            payload
        );

        webhookRepository.save(event);

        // Look up the merchant's webhook URL
        Merchant merchant = merchantRepository.findById(payment.getMerchantId()).orElse(null);
        if (merchant == null || merchant.getWebhookUrl() == null || merchant.getWebhookUrl().isBlank()) {
            System.out.println("    [Webhook] No webhook URL configured for merchant "
                               + payment.getMerchantId() + " — skipping");
            event.recordAttempt(true); // Mark as delivered (nothing to deliver to)
            return event;
        }

        // Generate HMAC-SHA256 signature
        String signature = generateSignature(payload, merchant.getApiKey());

        // Attempt delivery
        boolean delivered = attemptDelivery(event, merchant, signature);

        if (!delivered) {
            System.out.println("    [Webhook] First attempt failed. Scheduled retry with exponential backoff.");
            System.out.println("    [Webhook] Next retry at: " + event.getNextRetryAt());
        }

        return event;
    }

    /**
     * Attempt to deliver a webhook event to the merchant.
     *
     * @return true if delivery succeeded, false if it failed
     */
    private boolean attemptDelivery(WebhookEvent event, Merchant merchant, String signature) {
        System.out.println("    [Webhook] Delivering " + event.getEventType()
                           + " to " + merchant.getWebhookUrl()
                           + " (attempt " + (event.getAttempts() + 1) + "/" + event.getMaxAttempts() + ")");
        System.out.println("    [Webhook] Signature: " + signature.substring(0, 16) + "...");

        // ── Simulate HTTP POST ──
        // In production: HttpClient.send(POST, merchantUrl, payload, headers)
        boolean success;
        if (simulateFirstAttemptFailure && event.getAttempts() == 0) {
            // Force first attempt to fail for retry demo
            success = false;
            System.out.println("    [Webhook] Simulated failure (HTTP 503 Service Unavailable)");
        } else {
            // 90% success rate on normal attempts
            success = random.nextInt(100) < 90;
            if (success) {
                System.out.println("    [Webhook] Delivered successfully (HTTP 200 OK)");
            } else {
                System.out.println("    [Webhook] Delivery failed (HTTP 500 Internal Server Error)");
            }
        }

        event.recordAttempt(success);
        webhookRepository.save(event); // Update state
        return success;
    }

    /**
     * Retry delivering all FAILED webhook events.
     *
     * Called periodically by a background job (or manually for demo).
     * Only retries events whose nextRetryAt is in the past.
     *
     * @return number of events successfully delivered
     */
    public int deliverPendingWebhooks() {
        List<WebhookEvent> failedEvents = webhookRepository.findByStatus(WebhookStatus.FAILED);
        int delivered = 0;

        System.out.println("    [Webhook] Retrying " + failedEvents.size() + " failed webhook events...");

        for (WebhookEvent event : failedEvents) {
            // Check if it's time to retry
            if (event.getNextRetryAt() != null
                && event.getNextRetryAt().isAfter(java.time.LocalDateTime.now())) {
                System.out.println("    [Webhook] Skipping " + event.getEventId()
                                   + " — next retry at " + event.getNextRetryAt());
                continue;
            }

            Merchant merchant = merchantRepository.findById(event.getMerchantId()).orElse(null);
            if (merchant == null) continue;

            String signature = generateSignature(event.getPayload(), merchant.getApiKey());

            // On retry, don't force failure
            boolean previousSetting = simulateFirstAttemptFailure;
            simulateFirstAttemptFailure = false;

            boolean success = attemptDelivery(event, merchant, signature);

            simulateFirstAttemptFailure = previousSetting;

            if (success) delivered++;
        }

        System.out.println("    [Webhook] Retry complete: " + delivered + "/" + failedEvents.size() + " delivered");
        return delivered;
    }

    /**
     * Generate HMAC-SHA256 signature for webhook payload.
     *
     * In production, you'd use javax.crypto.Mac with HmacSHA256.
     * Here we simulate it with SHA-256 of (payload + apiKey) for simplicity.
     * The concept is the same: the merchant can verify authenticity.
     */
    private String generateSignature(String payload, String apiKey) {
        try {
            // Simulated HMAC: SHA-256(payload + apiKey)
            // Real implementation would use javax.crypto.Mac
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                (payload + apiKey).getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "signature-generation-failed";
        }
    }

    /**
     * Build a JSON-like payload string for the webhook event.
     */
    private String buildPayload(String eventType, Payment payment) {
        return "{"
               + "\"event\":\"" + eventType + "\","
               + "\"payment_id\":\"" + payment.getPaymentId() + "\","
               + "\"merchant_id\":\"" + payment.getMerchantId() + "\","
               + "\"amount\":" + payment.getAmount() + ","
               + "\"currency\":\"" + payment.getCurrency() + "\","
               + "\"status\":\"" + payment.getStatus() + "\","
               + "\"method\":\"" + payment.getMethod() + "\""
               + "}";
    }

    /**
     * Get all webhook events.
     */
    public List<WebhookEvent> getAllEvents() {
        return webhookRepository.findAll();
    }
}
