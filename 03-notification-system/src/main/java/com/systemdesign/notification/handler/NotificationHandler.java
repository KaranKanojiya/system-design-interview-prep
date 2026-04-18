package com.systemdesign.notification.handler;

import com.systemdesign.notification.model.Channel;
import com.systemdesign.notification.model.DeliveryAttempt;
import com.systemdesign.notification.model.Notification;

/**
 * Strategy interface for channel-specific delivery logic.
 * Each channel (PUSH, EMAIL, SMS, IN_APP) provides its own implementation.
 */
public interface NotificationHandler {

    /** Attempt delivery and return the result. */
    DeliveryAttempt send(Notification notification);

    /** The channel this handler is responsible for. */
    Channel supportedChannel();

    /** Health check — is this provider currently available? */
    boolean isAvailable();
}
