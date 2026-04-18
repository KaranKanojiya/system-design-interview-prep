package com.systemdesign.notification.handler;

import com.systemdesign.notification.model.*;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;

/**
 * Simulates push notification delivery via FCM/APNs.
 * 90% success rate to demonstrate retry scenarios.
 */
public class PushNotificationHandler implements NotificationHandler {

    private final Random random = new Random();

    @Override
    public DeliveryAttempt send(Notification notification) {
        System.out.printf("  [PUSH] Sending to user:%s -> %s%n",
                notification.getUserId(), notification.getSubject());

        boolean success = random.nextInt(100) < 90;
        String msgId = UUID.randomUUID().toString().substring(0, 8);

        NotificationStatus status = success ? NotificationStatus.SENT : NotificationStatus.FAILED;
        String response = success
                ? "FCM:msg_id_" + msgId
                : "FCM:device_not_registered";

        return new DeliveryAttempt(
                notification.getId(),
                notification.getRetryCount() + 1,
                status,
                response,
                LocalDateTime.now()
        );
    }

    @Override
    public Channel supportedChannel() {
        return Channel.PUSH;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
