package com.systemdesign.notification.repository;

import com.systemdesign.notification.model.NotificationTemplate;

import java.util.Optional;

/**
 * Persistence abstraction for notification templates.
 */
public interface TemplateRepository {

    Optional<NotificationTemplate> findById(String id);

    Optional<NotificationTemplate> findByName(String name);

    void save(NotificationTemplate template);
}
