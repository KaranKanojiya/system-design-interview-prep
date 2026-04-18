package com.systemdesign.notification.model;

/**
 * Lifecycle states of a notification from creation through delivery.
 */
public enum NotificationStatus {
    PENDING,
    QUEUED,
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
    BOUNCED,
    CANCELLED;

    /**
     * Terminal states indicate the notification lifecycle is complete
     * and no further processing should occur.
     */
    public boolean isTerminal() {
        return this == DELIVERED || this == FAILED || this == BOUNCED || this == CANCELLED;
    }
}
