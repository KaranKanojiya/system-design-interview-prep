package com.systemdesign.notification.queue;

import com.systemdesign.notification.model.Notification;

import java.util.concurrent.PriorityBlockingQueue;

/**
 * Thread-safe priority queue backed by PriorityBlockingQueue.
 * Notifications are ordered by priority value (CRITICAL first, LOW last)
 * via Notification's Comparable implementation.
 */
public class InMemoryPriorityQueue implements NotificationQueue {

    private final PriorityBlockingQueue<Notification> queue = new PriorityBlockingQueue<>();

    @Override
    public void enqueue(Notification notification) {
        queue.offer(notification);
    }

    @Override
    public Notification dequeue() {
        return queue.poll(); // non-blocking, returns null if empty
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
