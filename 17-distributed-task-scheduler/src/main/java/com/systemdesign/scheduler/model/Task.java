package com.systemdesign.scheduler.model;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Wiring: Core domain entity representing a schedulable unit of work.
// Created via Builder pattern, assigned to Workers by the Scheduler,
// executed by TaskExecutor, and tracked via TaskExecution records.
public class Task {

    private final String id;
    private final String name;
    private final String description;
    private final TaskType taskType;
    private final TaskPriority priority;
    private TaskStatus status;
    private final Map<String, String> payload;
    private final String cronExpression;
    private final long delayMillis;
    private final int maxRetries;
    private final long timeoutMillis;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Instant scheduledAt;
    private final String groupId;

    private Task(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.description = builder.description;
        this.taskType = builder.taskType;
        this.priority = builder.priority;
        this.status = builder.status;
        this.payload = Collections.unmodifiableMap(new HashMap<>(builder.payload));
        this.cronExpression = builder.cronExpression;
        this.delayMillis = builder.delayMillis;
        this.maxRetries = builder.maxRetries;
        this.timeoutMillis = builder.timeoutMillis;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
        this.scheduledAt = builder.scheduledAt;
        this.groupId = builder.groupId;
    }

    // --- Getters ---

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public TaskType getTaskType() { return taskType; }
    public TaskPriority getPriority() { return priority; }
    public TaskStatus getStatus() { return status; }
    public Map<String, String> getPayload() { return payload; }
    public String getCronExpression() { return cronExpression; }
    public long getDelayMillis() { return delayMillis; }
    public int getMaxRetries() { return maxRetries; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getScheduledAt() { return scheduledAt; }
    public String getGroupId() { return groupId; }

    // --- State transitions ---

    public void updateStatus(TaskStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "Task{id='" + id + "', name='" + name + "', type=" + taskType
                + ", priority=" + priority + ", status=" + status + "}";
    }

    // --- Builder ---

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private String id = UUID.randomUUID().toString();
        private final String name;
        private String description = "";
        private TaskType taskType = TaskType.ONE_TIME;
        private TaskPriority priority = TaskPriority.MEDIUM;
        private TaskStatus status = TaskStatus.PENDING;
        private Map<String, String> payload = new HashMap<>();
        private String cronExpression;
        private long delayMillis = 0;
        private int maxRetries = 3;
        private long timeoutMillis = 60_000;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();
        private Instant scheduledAt;
        private String groupId;

        public Builder(String name) {
            this.name = name;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder taskType(TaskType taskType) {
            this.taskType = taskType;
            return this;
        }

        public Builder priority(TaskPriority priority) {
            this.priority = priority;
            return this;
        }

        public Builder status(TaskStatus status) {
            this.status = status;
            return this;
        }

        public Builder payload(Map<String, String> payload) {
            this.payload = new HashMap<>(payload);
            return this;
        }

        public Builder addPayload(String key, String value) {
            this.payload.put(key, value);
            return this;
        }

        public Builder cronExpression(String cronExpression) {
            this.cronExpression = cronExpression;
            return this;
        }

        public Builder delayMillis(long delayMillis) {
            this.delayMillis = delayMillis;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder timeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public Builder scheduledAt(Instant scheduledAt) {
            this.scheduledAt = scheduledAt;
            return this;
        }

        public Builder groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public Task build() {
            return new Task(this);
        }
    }
}
