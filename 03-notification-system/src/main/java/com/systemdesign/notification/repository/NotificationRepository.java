package com.systemdesign.notification.repository;

import com.systemdesign.notification.model.Notification;
import com.systemdesign.notification.model.NotificationStatus;

import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for notifications.
 * In production, this would be backed by a database (e.g., DynamoDB, Cassandra).
 */
public interface NotificationRepository {

    void save(Notification notification);

    Optional<Notification> findById(String id);

    List<Notification> findByUserId(String userId);

    void updateStatus(String id, NotificationStatus status);

    /** Find all notifications eligible for retry (FAILED + retryCount < maxRetries). */
    List<Notification> findRetryable();
}
