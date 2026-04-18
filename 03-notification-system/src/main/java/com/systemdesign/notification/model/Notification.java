package com.systemdesign.notification.model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Core domain object representing a single notification to be delivered.
 * Uses the Builder pattern for flexible construction and implements
 * Comparable for priority-based queue ordering.
 */
public class Notification implements Comparable<Notification> {

    private final String id;
    private final String userId;
    private final String templateId;
    private final Channel channel;
    private final Priority priority;
    private NotificationStatus status;
    private String subject;
    private String body;
    private final Map<String, String> data;
    private final LocalDateTime scheduledAt;
    private LocalDateTime sentAt;
    private LocalDateTime deliveredAt;
    private int retryCount;
    private final int maxRetries;
    private final LocalDateTime createdAt;

    private Notification(Builder builder) {
        this.id = builder.id;
        this.userId = builder.userId;
        this.templateId = builder.templateId;
        this.channel = builder.channel;
        this.priority = builder.priority;
        this.status = builder.status;
        this.subject = builder.subject;
        this.body = builder.body;
        this.data = builder.data;
        this.scheduledAt = builder.scheduledAt;
        this.sentAt = builder.sentAt;
        this.deliveredAt = builder.deliveredAt;
        this.retryCount = builder.retryCount;
        this.maxRetries = builder.maxRetries;
        this.createdAt = builder.createdAt;
    }

    // --- State transition methods ---

    public void markAsSent() {
        this.status = NotificationStatus.SENT;
        this.sentAt = LocalDateTime.now();
    }

    public void markAsDelivered() {
        this.status = NotificationStatus.DELIVERED;
        this.deliveredAt = LocalDateTime.now();
    }

    public void markAsFailed() {
        this.status = NotificationStatus.FAILED;
    }

    public void incrementRetry() {
        this.retryCount++;
    }

    public boolean isRetryable() {
        return retryCount < maxRetries && status == NotificationStatus.FAILED;
    }

    // --- Comparable: lower priority value = higher urgency = dequeued first ---

    @Override
    public int compareTo(Notification other) {
        return Integer.compare(this.priority.getValue(), other.priority.getValue());
    }

    // --- Getters ---

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getTemplateId() { return templateId; }
    public Channel getChannel() { return channel; }
    public Priority getPriority() { return priority; }
    public NotificationStatus getStatus() { return status; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Map<String, String> getData() { return data; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public LocalDateTime getSentAt() { return sentAt; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setStatus(NotificationStatus status) { this.status = status; }
    public void setSubject(String subject) { this.subject = subject; }
    public void setBody(String body) { this.body = body; }

    @Override
    public String toString() {
        return String.format("Notification{id='%s', user='%s', channel=%s, priority=%s, status=%s, subject='%s', retries=%d/%d}",
                id, userId, channel, priority, status, subject, retryCount, maxRetries);
    }

    // --- Builder ---

    public static class Builder {
        private String id = UUID.randomUUID().toString().substring(0, 8);
        private String userId;
        private String templateId;
        private Channel channel;
        private Priority priority = Priority.MEDIUM;
        private NotificationStatus status = NotificationStatus.PENDING;
        private String subject;
        private String body;
        private Map<String, String> data = new HashMap<>();
        private LocalDateTime scheduledAt;
        private LocalDateTime sentAt;
        private LocalDateTime deliveredAt;
        private int retryCount = 0;
        private int maxRetries = 3;
        private LocalDateTime createdAt = LocalDateTime.now();

        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder templateId(String templateId) { this.templateId = templateId; return this; }
        public Builder channel(Channel channel) { this.channel = channel; return this; }
        public Builder priority(Priority priority) { this.priority = priority; return this; }
        public Builder status(NotificationStatus status) { this.status = status; return this; }
        public Builder subject(String subject) { this.subject = subject; return this; }
        public Builder body(String body) { this.body = body; return this; }
        public Builder data(Map<String, String> data) { this.data = new HashMap<>(data); return this; }
        public Builder scheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; return this; }
        public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public Builder id(String id) { this.id = id; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public Notification build() {
            if (userId == null || channel == null) {
                throw new IllegalArgumentException("userId and channel are required");
            }
            return new Notification(this);
        }
    }
}
