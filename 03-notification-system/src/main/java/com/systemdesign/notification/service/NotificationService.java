package com.systemdesign.notification.service;

import com.systemdesign.notification.handler.NotificationHandler;
import com.systemdesign.notification.model.*;
import com.systemdesign.notification.queue.NotificationQueue;
import com.systemdesign.notification.repository.NotificationRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Core orchestrator: accepts requests, enforces preferences, renders templates,
 * enqueues notifications, processes the queue, and handles retries.
 */
public class NotificationService {

    private final NotificationRepository repository;
    private final PreferenceService preferenceService;
    private final TemplateService templateService;
    private final DeliveryTracker deliveryTracker;
    private final NotificationQueue queue;
    private final Map<Channel, NotificationHandler> handlers;

    public NotificationService(NotificationRepository repository,
                               PreferenceService preferenceService,
                               TemplateService templateService,
                               DeliveryTracker deliveryTracker,
                               NotificationQueue queue,
                               Map<Channel, NotificationHandler> handlers) {
        this.repository = repository;
        this.preferenceService = preferenceService;
        this.templateService = templateService;
        this.deliveryTracker = deliveryTracker;
        this.queue = queue;
        this.handlers = handlers;
    }

    /**
     * Accept a notification request, build notifications for each user, and enqueue them.
     * Returns the notification ID for single requests, or "batch:{count}" for batch.
     */
    public String send(NotificationRequest request) {
        List<String> createdIds = new ArrayList<>();

        for (String userId : request.getUserIds()) {
            // 1. Check user preferences
            if (!preferenceService.canSend(userId, request.getChannel(), LocalDateTime.now())) {
                System.out.printf("  [SKIP] User '%s' opted out or in quiet hours for %s%n",
                        userId, request.getChannel().getDisplayName());
                continue;
            }

            // 2. Render template
            String[] rendered = templateService.renderTemplate(request.getTemplateId(), request.getData());

            // 3. Determine max retries based on channel
            int maxRetries = switch (request.getChannel()) {
                case PUSH, EMAIL -> 5;
                case SMS -> 3;
                case IN_APP -> 0;
            };

            // 4. Build notification
            Notification notification = new Notification.Builder()
                    .userId(userId)
                    .templateId(request.getTemplateId())
                    .channel(request.getChannel())
                    .priority(request.getPriority())
                    .status(NotificationStatus.PENDING)
                    .subject(rendered[0])
                    .body(rendered[1])
                    .data(request.getData())
                    .maxRetries(maxRetries)
                    .build();

            // 5. Persist
            repository.save(notification);

            // 6. Enqueue
            notification.setStatus(NotificationStatus.QUEUED);
            queue.enqueue(notification);
            createdIds.add(notification.getId());
        }

        if (createdIds.isEmpty()) {
            return "none:all_skipped";
        }

        return request.isBatch()
                ? "batch:" + createdIds.size()
                : createdIds.getFirst();
    }

    /**
     * Process up to maxBatch notifications from the queue.
     * For each: deliver via the appropriate handler, track the attempt, and update status.
     */
    public int processQueue(int maxBatch) {
        int processed = 0;

        for (int i = 0; i < maxBatch; i++) {
            Notification notification = queue.dequeue();
            if (notification == null) break;

            NotificationHandler handler = handlers.get(notification.getChannel());
            if (handler == null) {
                System.out.printf("  [ERROR] No handler for channel: %s%n", notification.getChannel());
                notification.markAsFailed();
                repository.save(notification);
                continue;
            }

            // Mark as sending
            notification.setStatus(NotificationStatus.SENDING);

            // Attempt delivery
            DeliveryAttempt attempt = handler.send(notification);
            deliveryTracker.record(attempt);

            if (attempt.getStatus() == NotificationStatus.SENT) {
                notification.markAsSent();
                notification.markAsDelivered();
                repository.save(notification);
            } else {
                notification.markAsFailed();
                notification.incrementRetry();
                repository.save(notification);

                if (notification.isRetryable()) {
                    System.out.printf("  [RETRY] Notification %s queued for retry (%d/%d)%n",
                            notification.getId(), notification.getRetryCount(), notification.getMaxRetries());
                    queue.enqueue(notification);
                } else {
                    System.out.printf("  [EXHAUSTED] Notification %s has no retries left%n",
                            notification.getId());
                }
            }

            processed++;
        }

        return processed;
    }

    /**
     * Find all retryable notifications from the repository and re-enqueue them.
     */
    public int retryFailed() {
        List<Notification> retryable = repository.findRetryable();
        for (Notification n : retryable) {
            n.setStatus(NotificationStatus.QUEUED);
            queue.enqueue(n);
        }
        System.out.printf("  [RETRY] Re-enqueued %d failed notifications%n", retryable.size());
        return retryable.size();
    }

    // --- Accessors for controller/demo use ---

    public NotificationRepository getRepository() { return repository; }
    public DeliveryTracker getDeliveryTracker() { return deliveryTracker; }
}
