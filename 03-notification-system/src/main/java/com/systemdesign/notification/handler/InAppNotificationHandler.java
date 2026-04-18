package com.systemdesign.notification.handler;

import com.systemdesign.notification.model.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * In-app notifications are stored directly — always succeeds.
 */
public class InAppNotificationHandler implements NotificationHandler {

    @Override
    public DeliveryAttempt send(Notification notification) {
        System.out.printf("  [IN-APP] Stored for user:%s -> %s%n",
                notification.getUserId(), notification.getSubject());

        String notifId = UUID.randomUUID().toString().substring(0, 8);

        return new DeliveryAttempt(
                notification.getId(),
                notification.getRetryCount() + 1,
                NotificationStatus.SENT,
                "stored:notif_" + notifId,
                LocalDateTime.now()
        );
    }

    @Override
    public Channel supportedChannel() {
        return Channel.IN_APP;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
