package com.systemdesign.notification.repository;

import com.systemdesign.notification.model.UserPreference;

import java.util.Optional;

/**
 * Persistence abstraction for user notification preferences.
 */
public interface PreferenceRepository {

    Optional<UserPreference> findByUserId(String userId);

    void save(UserPreference preference);
}
