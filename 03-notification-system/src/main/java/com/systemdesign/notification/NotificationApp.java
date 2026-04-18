package com.systemdesign.notification;

import com.systemdesign.notification.config.AppConfig;
import com.systemdesign.notification.controller.NotificationController;
import com.systemdesign.notification.model.*;

import java.util.List;
import java.util.Map;

/**
 * Main demo application showcasing the Notification System design.
 * Runs through multiple scenarios to demonstrate key features:
 * single/batch delivery, preference enforcement, retry logic,
 * multi-channel support, and priority ordering.
 */
public class NotificationApp {

    public static void main(String[] args) {
        System.out.println("=== Notification System — System Design Demo ===\n");

        // Wire up the entire system via AppConfig (manual DI)
        AppConfig config = new AppConfig();
        NotificationController controller = config.createController();

        // ---------------------------------------------------------------
        // Demo 1: Single Email Notification (order confirmation)
        // ---------------------------------------------------------------
        System.out.println("\n========== Demo 1: Single Email Notification ==========");
        String emailId = controller.handleSend(NotificationRequest.single(
                "alice", "order-confirmation", Channel.EMAIL, Priority.HIGH,
                Map.of("name", "Alice", "orderId", "ORD-1234",
                        "item", "MacBook Pro", "amount", "$2499")
        ));
        controller.handleProcessQueue(10);
        controller.handleGetStatus(emailId);

        // ---------------------------------------------------------------
        // Demo 2: OTP via SMS (Critical Priority)
        // ---------------------------------------------------------------
        System.out.println("\n========== Demo 2: OTP via SMS (Critical Priority) ==========");
        String otpId = controller.handleSend(NotificationRequest.single(
                "alice", "otp-verification", Channel.SMS, Priority.CRITICAL,
                Map.of("otp", "847293", "minutes", "5")
        ));
        controller.handleProcessQueue(10);
        controller.handleGetStatus(otpId);

        // ---------------------------------------------------------------
        // Demo 3: User Preference Enforcement (Bob has SMS disabled)
        // ---------------------------------------------------------------
        System.out.println("\n========== Demo 3: User Preference Enforcement ==========");
        String skippedId = controller.handleSend(NotificationRequest.single(
                "bob", "otp-verification", Channel.SMS, Priority.CRITICAL,
                Map.of("otp", "112233", "minutes", "5")
        ));
        System.out.printf("  Note: Bob has SMS disabled -> result: %s%n", skippedId);

        // ---------------------------------------------------------------
        // Demo 4: Push Notification with Retry Logic
        // ---------------------------------------------------------------
        System.out.println("\n========== Demo 4: Push Notification with Retry ==========");
        controller.handleSend(NotificationRequest.single(
                "alice", "price-drop-alert", Channel.PUSH, Priority.MEDIUM,
                Map.of("item", "AirPods Pro", "newPrice", "$199",
                        "oldPrice", "$249", "discount", "20")
        ));
        controller.handleSend(NotificationRequest.single(
                "bob", "price-drop-alert", Channel.PUSH, Priority.MEDIUM,
                Map.of("item", "iPad Air", "newPrice", "$499",
                        "oldPrice", "$599", "discount", "17")
        ));
        System.out.println("\n  --- First pass (some may fail with 90% success rate) ---");
        controller.handleProcessQueue(10);

        System.out.println("\n  --- Retrying failed notifications ---");
        controller.handleRetryFailed();
        controller.handleProcessQueue(10);

        // ---------------------------------------------------------------
        // Demo 5: Batch Notification (fan-out to multiple users)
        // ---------------------------------------------------------------
        System.out.println("\n========== Demo 5: Batch Notification ==========");
        String batchResult = controller.handleSend(NotificationRequest.batch(
                List.of("alice", "bob", "carol"),
                "price-drop-alert", Channel.PUSH, Priority.LOW,
                Map.of("item", "Sony WH-1000XM5", "newPrice", "$298",
                        "oldPrice", "$398", "discount", "25")
        ));
        System.out.printf("  Batch result: %s (carol should be skipped — quiet hours)%n", batchResult);
        controller.handleProcessQueue(10);

        // ---------------------------------------------------------------
        // Demo 6: Multi-Channel — same info, all 4 channels
        // ---------------------------------------------------------------
        System.out.println("\n========== Demo 6: Multi-Channel Delivery ==========");
        Map<String, String> welcomeData = Map.of("name", "Alice");

        controller.handleSend(NotificationRequest.single(
                "alice", "welcome-message", Channel.IN_APP, Priority.LOW, welcomeData));
        // Use order-confirmation for email channel (already seeded)
        controller.handleSend(NotificationRequest.single(
                "alice", "order-confirmation", Channel.EMAIL, Priority.LOW,
                Map.of("name", "Alice", "orderId", "ORD-5678",
                        "item", "Magic Keyboard", "amount", "$299")));
        controller.handleSend(NotificationRequest.single(
                "alice", "otp-verification", Channel.SMS, Priority.LOW,
                Map.of("otp", "999888", "minutes", "10")));
        controller.handleSend(NotificationRequest.single(
                "alice", "price-drop-alert", Channel.PUSH, Priority.LOW,
                Map.of("item", "HomePod Mini", "newPrice", "$79",
                        "oldPrice", "$99", "discount", "20")));

        System.out.println("\n  --- Processing all 4 channels ---");
        controller.handleProcessQueue(20);

        // ---------------------------------------------------------------
        // Delivery Statistics
        // ---------------------------------------------------------------
        controller.getService().getDeliveryTracker().printStats();

        // ---------------------------------------------------------------
        // Design Summary
        // ---------------------------------------------------------------
        System.out.println("""

                ============================================================
                            DESIGN SUMMARY & PATTERNS USED
                ============================================================

                1. Strategy Pattern
                   - NotificationHandler interface with per-channel implementations
                   - Easily extensible: add WhatsApp, Slack, etc. without modifying core

                2. Builder Pattern
                   - Notification.Builder for clean, flexible object construction
                   - Avoids telescoping constructors

                3. Repository Pattern
                   - Interface-based data access (NotificationRepository, etc.)
                   - Swap InMemory for DynamoDB/Cassandra without changing services

                4. Template Method
                   - SimpleTemplateEngine decouples content from delivery
                   - Templates stored in DB, rendered at send time

                5. Priority Queue
                   - PriorityBlockingQueue orders by priority (CRITICAL > HIGH > MEDIUM > LOW)
                   - Thread-safe for concurrent producers/consumers

                6. Retry with Backoff
                   - Channel-specific retry limits (PUSH=5, EMAIL=5, SMS=3, IN_APP=0)
                   - Failed notifications re-enqueued for retry

                7. User Preferences
                   - Per-channel opt-in/out, quiet hours, frequency caps
                   - Checked before enqueuing to avoid unnecessary work

                SCALABILITY NOTES:
                - Queue: Replace InMemoryPriorityQueue with Kafka/SQS for distributed processing
                - Storage: Replace InMemory repos with DynamoDB/Cassandra for persistence
                - Handlers: Deploy as separate microservices for independent scaling
                - Rate limiting: Add token bucket per-channel to respect provider limits
                - Idempotency: Add dedup key to prevent duplicate sends
                - Monitoring: Add metrics (Prometheus) and distributed tracing (OpenTelemetry)
                ============================================================
                """);
    }
}
