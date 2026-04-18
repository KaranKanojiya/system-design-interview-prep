package com.systemdesign.notification.model;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-user notification preferences: channel opt-in/out, quiet hours,
 * and daily frequency caps. Defaults are sensible for most users.
 */
public class UserPreference {

    private final String userId;
    private final Map<Channel, Boolean> channelEnabled;
    private int quietHoursStart; // 0-23
    private int quietHoursEnd;   // 0-23
    private final Map<Channel, Integer> dailyFrequencyCap;

    public UserPreference(String userId) {
        this.userId = userId;

        // Default: all channels enabled
        this.channelEnabled = new EnumMap<>(Channel.class);
        for (Channel ch : Channel.values()) {
            channelEnabled.put(ch, true);
        }

        // Default quiet hours: 22:00 - 08:00
        this.quietHoursStart = 22;
        this.quietHoursEnd = 8;

        // Default daily frequency caps
        this.dailyFrequencyCap = new EnumMap<>(Channel.class);
        dailyFrequencyCap.put(Channel.PUSH, 50);
        dailyFrequencyCap.put(Channel.EMAIL, 10);
        dailyFrequencyCap.put(Channel.SMS, 5);
        dailyFrequencyCap.put(Channel.IN_APP, 100);
    }

    public boolean isChannelEnabled(Channel channel) {
        return channelEnabled.getOrDefault(channel, true);
    }

    /**
     * Check if the given time falls within the user's quiet hours window.
     * Handles overnight spans (e.g., 22:00 - 08:00).
     */
    public boolean isQuietHours(LocalDateTime now) {
        int hour = now.getHour();
        if (quietHoursStart <= quietHoursEnd) {
            return hour >= quietHoursStart && hour < quietHoursEnd;
        }
        // Overnight span (e.g., 22 to 8)
        return hour >= quietHoursStart || hour < quietHoursEnd;
    }

    public int getFrequencyCap(Channel channel) {
        return dailyFrequencyCap.getOrDefault(channel, Integer.MAX_VALUE);
    }

    // --- Mutators for configuration ---

    public void setChannelEnabled(Channel channel, boolean enabled) {
        channelEnabled.put(channel, enabled);
    }

    public void setQuietHoursStart(int quietHoursStart) {
        this.quietHoursStart = quietHoursStart;
    }

    public void setQuietHoursEnd(int quietHoursEnd) {
        this.quietHoursEnd = quietHoursEnd;
    }

    public void setDailyFrequencyCap(Channel channel, int cap) {
        dailyFrequencyCap.put(channel, cap);
    }

    // --- Getters ---

    public String getUserId() { return userId; }
    public Map<Channel, Boolean> getChannelEnabled() { return channelEnabled; }
    public int getQuietHoursStart() { return quietHoursStart; }
    public int getQuietHoursEnd() { return quietHoursEnd; }
    public Map<Channel, Integer> getDailyFrequencyCap() { return dailyFrequencyCap; }
}
