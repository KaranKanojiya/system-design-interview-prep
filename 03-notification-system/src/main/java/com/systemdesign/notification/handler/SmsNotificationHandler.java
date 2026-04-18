package com.systemdesign.notification.handler;

import com.systemdesign.notification.model.*;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;

/**
 * Simulates SMS delivery via Twilio.
 * 85% success rate to demonstrate higher failure and retry scenarios.
 */
public class SmsNotificationHandler implements NotificationHandler {

    private final Random random = new Random();

    @Override
    public DeliveryAttempt send(Notification notification) {
        System.out.printf("  [SMS] Sending to user:%s -> %s%n",
                notification.getUserId(), notification.getBody());

        boolean success = random.nextInt(100) < 85;
        String msgId = UUID.randomUUID().toString().substring(0, 8);

        NotificationStatus status = success ? NotificationStatus.SENT : NotificationStatus.FAILED;
        String response = success
                ? "Twilio:SM_" + msgId
                : "Twilio:undeliverable";

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
        return Channel.SMS;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
