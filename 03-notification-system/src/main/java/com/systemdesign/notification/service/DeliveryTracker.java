package com.systemdesign.notification.service;

import com.systemdesign.notification.model.DeliveryAttempt;
import com.systemdesign.notification.model.NotificationStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks all delivery attempts for observability, auditing, and retry decisions.
 */
public class DeliveryTracker {

    private final Map<String, List<DeliveryAttempt>> attempts = new ConcurrentHashMap<>();

    public void record(DeliveryAttempt attempt) {
        attempts.computeIfAbsent(attempt.getNotificationId(), k -> new ArrayList<>())
                .add(attempt);
    }

    public List<DeliveryAttempt> getAttempts(String notificationId) {
        return attempts.getOrDefault(notificationId, List.of());
    }

    /**
     * Print aggregate delivery statistics across all tracked notifications.
     */
    public void printStats() {
        long totalAttempts = attempts.values().stream().mapToLong(List::size).sum();
        long sent = attempts.values().stream().flatMap(List::stream)
                .filter(a -> a.getStatus() == NotificationStatus.SENT).count();
        long delivered = attempts.values().stream().flatMap(List::stream)
                .filter(a -> a.getStatus() == NotificationStatus.DELIVERED).count();
        long failed = attempts.values().stream().flatMap(List::stream)
                .filter(a -> a.getStatus() == NotificationStatus.FAILED).count();

        System.out.println("\n--- Delivery Statistics ---");
        System.out.printf("  Total attempts:  %d%n", totalAttempts);
        System.out.printf("  Sent:            %d%n", sent);
        System.out.printf("  Delivered:       %d%n", delivered);
        System.out.printf("  Failed:          %d%n", failed);
        System.out.printf("  Unique notifications tracked: %d%n", attempts.size());
        System.out.println("---------------------------");
    }
}
