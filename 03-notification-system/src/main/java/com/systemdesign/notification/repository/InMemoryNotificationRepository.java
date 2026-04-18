package com.systemdesign.notification.repository;

import com.systemdesign.notification.model.Notification;
import com.systemdesign.notification.model.NotificationStatus;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation using ConcurrentHashMap for thread safety.
 * Suitable for demos; production would use a persistent store.
 */
public class InMemoryNotificationRepository implements NotificationRepository {

    private final ConcurrentHashMap<String, Notification> store = new ConcurrentHashMap<>();

    @Override
    public void save(Notification notification) {
        store.put(notification.getId(), notification);
    }

    @Override
    public Optional<Notification> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Notification> findByUserId(String userId) {
        return store.values().stream()
                .filter(n -> n.getUserId().equals(userId))
                .toList();
    }

    @Override
    public void updateStatus(String id, NotificationStatus status) {
        Notification n = store.get(id);
        if (n != null) {
            n.setStatus(status);
        }
    }

    @Override
    public List<Notification> findRetryable() {
        return store.values().stream()
                .filter(Notification::isRetryable)
                .toList();
    }
}
