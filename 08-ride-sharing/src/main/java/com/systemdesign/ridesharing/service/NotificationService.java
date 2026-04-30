package com.systemdesign.ridesharing.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * NotificationService — Simulated push notification system.
 *
 * In production Uber:
 *   Notifications are sent via multiple channels:
 *   - Push notifications (APNs for iOS, FCM for Android)
 *   - SMS (fallback for unreliable push)
 *   - In-app real-time updates (WebSocket/SSE)
 *   - Email (for receipts, not time-sensitive)
 *
 *   The notification service is event-driven:
 *   - Ride MATCHED -> notify rider "Driver on the way" + notify driver "New ride"
 *   - Driver EN_ROUTE -> notify rider "Driver is arriving"
 *   - Ride COMPLETED -> notify rider with receipt
 *   - Ride CANCELLED -> notify both parties
 *
 * Here we simulate by printing to console with timestamps.
 */
public class NotificationService {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    /**
     * Send a notification to a rider.
     *
     * @param riderId rider's ID
     * @param message notification message
     */
    public void notifyRider(String riderId, String message) {
        String timestamp = FORMATTER.format(Instant.now());
        System.out.printf("  [Notification %s] -> Rider '%s': %s%n",
                timestamp, riderId, message);
    }

    /**
     * Send a notification to a driver.
     *
     * @param driverId driver's ID
     * @param message  notification message
     */
    public void notifyDriver(String driverId, String message) {
        String timestamp = FORMATTER.format(Instant.now());
        System.out.printf("  [Notification %s] -> Driver '%s': %s%n",
                timestamp, driverId, message);
    }
}
