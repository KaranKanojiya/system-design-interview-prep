package com.systemdesign.notification.service;

import com.systemdesign.notification.model.Channel;
import com.systemdesign.notification.model.UserPreference;
import com.systemdesign.notification.repository.PreferenceRepository;

import java.time.LocalDateTime;

/**
 * Evaluates user preferences to determine whether a notification
 * should be sent (channel opt-in, quiet hours, frequency caps).
 */
public class PreferenceService {

    private final PreferenceRepository preferenceRepository;

    public PreferenceService(PreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    /**
     * Check if a notification can be sent to the user on the given channel at the given time.
     * If no preference is found, default to allowing the notification.
     */
    public boolean canSend(String userId, Channel channel, LocalDateTime now) {
        UserPreference pref = getOrDefault(userId);

        if (!pref.isChannelEnabled(channel)) {
            return false;
        }

        if (pref.isQuietHours(now)) {
            return false;
        }

        return true;
    }

    /**
     * Return saved preferences or create a default preference for the user.
     */
    public UserPreference getOrDefault(String userId) {
        return preferenceRepository.findByUserId(userId)
                .orElseGet(() -> new UserPreference(userId));
    }
}
