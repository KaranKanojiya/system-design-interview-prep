package com.systemdesign.notification.repository;

import com.systemdesign.notification.model.UserPreference;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory user preference store.
 */
public class InMemoryPreferenceRepository implements PreferenceRepository {

    private final ConcurrentHashMap<String, UserPreference> store = new ConcurrentHashMap<>();

    @Override
    public Optional<UserPreference> findByUserId(String userId) {
        return Optional.ofNullable(store.get(userId));
    }

    @Override
    public void save(UserPreference preference) {
        store.put(preference.getUserId(), preference);
    }
}
