package com.systemdesign.notification.model;

import java.time.LocalDateTime;

/**
 * Records a single delivery attempt for audit and retry tracking.
 */
public class DeliveryAttempt {

    private final String notificationId;
    private final int attemptNumber;
    private final NotificationStatus status;
    private final String providerResponse;
    private final LocalDateTime timestamp;

    public DeliveryAttempt(String notificationId, int attemptNumber,
                           NotificationStatus status, String providerResponse,
                           LocalDateTime timestamp) {
        this.notificationId = notificationId;
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.providerResponse = providerResponse;
        this.timestamp = timestamp;
    }

    public String getNotificationId() { return notificationId; }
    public int getAttemptNumber() { return attemptNumber; }
    public NotificationStatus getStatus() { return status; }
    public String getProviderResponse() { return providerResponse; }
    public LocalDateTime getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("DeliveryAttempt{notifId='%s', attempt=%d, status=%s, response='%s', at=%s}",
                notificationId, attemptNumber, status, providerResponse, timestamp);
    }
}
