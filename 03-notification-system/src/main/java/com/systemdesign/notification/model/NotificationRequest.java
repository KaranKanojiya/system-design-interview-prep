package com.systemdesign.notification.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Inbound request to send notifications. Supports both single-user
 * and batch (fan-out) scenarios via factory methods.
 */
public class NotificationRequest {

    private final List<String> userIds;
    private final String templateId;
    private final Channel channel;
    private final Priority priority;
    private final Map<String, String> data;
    private final LocalDateTime scheduledAt;

    public NotificationRequest(List<String> userIds, String templateId, Channel channel,
                               Priority priority, Map<String, String> data, LocalDateTime scheduledAt) {
        this.userIds = List.copyOf(userIds);
        this.templateId = templateId;
        this.channel = channel;
        this.priority = priority;
        this.data = data != null ? Map.copyOf(data) : Map.of();
        this.scheduledAt = scheduledAt;
    }

    /** Factory for sending to a single user. */
    public static NotificationRequest single(String userId, String templateId, Channel channel,
                                             Priority priority, Map<String, String> data) {
        return new NotificationRequest(List.of(userId), templateId, channel, priority, data, null);
    }

    /** Factory for fan-out to multiple users. */
    public static NotificationRequest batch(List<String> userIds, String templateId, Channel channel,
                                            Priority priority, Map<String, String> data) {
        return new NotificationRequest(userIds, templateId, channel, priority, data, null);
    }

    public boolean isBatch() {
        return userIds.size() > 1;
    }

    // --- Getters ---

    public List<String> getUserIds() { return userIds; }
    public String getTemplateId() { return templateId; }
    public Channel getChannel() { return channel; }
    public Priority getPriority() { return priority; }
    public Map<String, String> getData() { return data; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
}
