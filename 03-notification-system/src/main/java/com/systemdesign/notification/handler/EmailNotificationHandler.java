package com.systemdesign.notification.handler;

import com.systemdesign.notification.model.*;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;

/**
 * Simulates email delivery via AWS SES.
 * 95% success rate.
 */
public class EmailNotificationHandler implements NotificationHandler {

    private final Random random = new Random();

    @Override
    public DeliveryAttempt send(Notification notification) {
        System.out.printf("  [EMAIL] Sending to user:%s -> %s%n",
                notification.getUserId(), notification.getSubject());

        boolean success = random.nextInt(100) < 95;
        String msgId = UUID.randomUUID().toString().substring(0, 8);

        NotificationStatus status = success ? NotificationStatus.SENT : NotificationStatus.FAILED;
        String response = success
                ? "SES:message_id_" + msgId
                : "SES:bounce_invalid_address";

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
        return Channel.EMAIL;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
