package com.systemdesign.notification.controller;

import com.systemdesign.notification.model.DeliveryAttempt;
import com.systemdesign.notification.model.Notification;
import com.systemdesign.notification.model.NotificationRequest;
import com.systemdesign.notification.service.NotificationService;

import java.util.List;
import java.util.Optional;

/**
 * Simulated REST controller — demonstrates the API surface
 * that would exist in a real web framework (Spring, Javalin, etc.).
 */
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public String handleSend(NotificationRequest request) {
        System.out.println("\n[POST /api/notifications/send]");
        String result = notificationService.send(request);
        System.out.printf("  -> Result: %s%n", result);
        return result;
    }

    public int handleProcessQueue(int batch) {
        System.out.println("\n[PROCESS] Processing queue...");
        int count = notificationService.processQueue(batch);
        System.out.printf("  -> Processed: %d notifications%n", count);
        return count;
    }

    public void handleGetStatus(String notificationId) {
        System.out.printf("%n[GET /api/notifications/%s/status]%n", notificationId);

        Optional<Notification> notif = notificationService.getRepository().findById(notificationId);
        if (notif.isPresent()) {
            Notification n = notif.get();
            System.out.printf("  Status: %s%n", n.getStatus());
            System.out.printf("  Channel: %s | Priority: %s%n", n.getChannel(), n.getPriority());
            System.out.printf("  Subject: %s%n", n.getSubject());
            System.out.printf("  Retries: %d/%d%n", n.getRetryCount(), n.getMaxRetries());

            List<DeliveryAttempt> attempts = notificationService.getDeliveryTracker()
                    .getAttempts(notificationId);
            if (!attempts.isEmpty()) {
                System.out.println("  Delivery attempts:");
                for (DeliveryAttempt a : attempts) {
                    System.out.printf("    #%d: %s — %s%n",
                            a.getAttemptNumber(), a.getStatus(), a.getProviderResponse());
                }
            }
        } else {
            System.out.printf("  Notification '%s' not found%n", notificationId);
        }
    }

    public int handleRetryFailed() {
        System.out.println("\n[POST /api/notifications/retry]");
        return notificationService.retryFailed();
    }

    public NotificationService getService() {
        return notificationService;
    }
}
