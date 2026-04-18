package com.systemdesign.notification.queue;

import com.systemdesign.notification.model.Notification;

/**
 * Abstraction over the notification processing queue.
 * Implementations may be in-memory, Redis-backed, Kafka-backed, etc.
 */
public interface NotificationQueue {

    void enqueue(Notification notification);

    /** Dequeue the highest-priority notification, or null if empty. */
    Notification dequeue();

    int size();

    boolean isEmpty();
}
