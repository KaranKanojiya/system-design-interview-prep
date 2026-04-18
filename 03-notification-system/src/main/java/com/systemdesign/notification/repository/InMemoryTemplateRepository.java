package com.systemdesign.notification.repository;

import com.systemdesign.notification.model.NotificationTemplate;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory template store. findByName scans all values since
 * name is not the primary key.
 */
public class InMemoryTemplateRepository implements TemplateRepository {

    private final ConcurrentHashMap<String, NotificationTemplate> store = new ConcurrentHashMap<>();

    @Override
    public Optional<NotificationTemplate> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<NotificationTemplate> findByName(String name) {
        return store.values().stream()
                .filter(t -> t.getName().equals(name))
                .findFirst();
    }

    @Override
    public void save(NotificationTemplate template) {
        store.put(template.getId(), template);
    }
}
