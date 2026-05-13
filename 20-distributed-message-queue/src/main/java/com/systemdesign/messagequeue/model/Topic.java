package com.systemdesign.messagequeue.model;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Topic definition — a named channel that producers write to and consumers read from.
 * Each topic is split into partitions for parallelism, and replicated for durability.
 */
public class Topic {

    private static final long DEFAULT_RETENTION_MS = 604_800_000L; // 7 days

    // --- identity ---
    private final String name;                 // unique topic name
    private final int partitionCount;          // number of partitions
    private final int replicationFactor;       // number of replicas per partition

    // --- retention ---
    private long retentionMs;                  // how long to keep messages (ms)

    // --- metadata ---
    private final Instant createdAt;           // creation timestamp
    private final Map<String, String> config;  // topic-level config overrides

    public Topic(String name, int partitionCount, int replicationFactor) {
        this.name = name;
        this.partitionCount = partitionCount;
        this.replicationFactor = replicationFactor;
        this.retentionMs = DEFAULT_RETENTION_MS;
        this.createdAt = Instant.now();
        this.config = new HashMap<>();
    }

    // --- getters ---

    public String getName() { return name; }
    public int getPartitionCount() { return partitionCount; }
    public int getReplicationFactor() { return replicationFactor; }
    public long getRetentionMs() { return retentionMs; }
    public Instant getCreatedAt() { return createdAt; }
    public Map<String, String> getConfig() { return config; }

    // --- setters ---

    public void setRetentionMs(long retentionMs) { this.retentionMs = retentionMs; }

    public void setConfig(String key, String value) {
        this.config.put(key, value);
    }

    // --- convenience ---

    /** Returns the retention period as a Duration. */
    public Duration getRetentionDuration() {
        return Duration.ofMillis(retentionMs);
    }

    @Override
    public String toString() {
        return "Topic{name='" + name + "', partitions=" + partitionCount
                + ", replication=" + replicationFactor
                + ", retentionMs=" + retentionMs + "}";
    }
}
