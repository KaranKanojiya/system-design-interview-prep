package com.systemdesign.payment.exception;

/**
 * WebhookDeliveryException — Thrown when a webhook delivery attempt fails.
 *
 * This triggers the retry mechanism in WebhookService.
 * Unlike other exceptions, this is expected and handled gracefully —
 * the webhook will be retried with exponential backoff.
 */
public class WebhookDeliveryException extends PaymentException {

    private final String eventId;
    private final int attemptNumber;

    public WebhookDeliveryException(String eventId, int attemptNumber, String message) {
        super("Webhook delivery failed for event " + eventId
              + " (attempt " + attemptNumber + "): " + message);
        this.eventId = eventId;
        this.attemptNumber = attemptNumber;
    }

    public String getEventId() { return eventId; }
    public int getAttemptNumber() { return attemptNumber; }
}
