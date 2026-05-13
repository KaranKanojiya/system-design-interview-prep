package com.systemdesign.messagequeue.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all partition commit logs across all topics.
 *
 * Each partition is identified by a composite key "topicName-partitionId".
 * The PartitionManager is the single source of truth for partition lifecycle:
 * create, lookup, delete.
 *
 * Flow:
 *  1. TopicService creates a topic with N partitions
 *  2. For each partition, TopicService calls createPartition()
 *  3. Producers/consumers look up partitions via getPartition()
 */
public class PartitionManager {

    // --- storage: key = "topicName-partitionId" ---
    private final Map<String, CommitLog> commitLogs;

    public PartitionManager() {
        this.commitLogs = new ConcurrentHashMap<>();
    }

    // ===================== Partition Lifecycle =====================

    /**
     * Creates a new CommitLog for the given topic and partition.
     * If the partition already exists, this is a no-op.
     *
     * @param topicName   the topic name
     * @param partitionId the partition index
     */
    public void createPartition(String topicName, int partitionId) {
        String key = getPartitionKey(topicName, partitionId);
        // putIfAbsent — idempotent creation
        commitLogs.putIfAbsent(key, new CommitLog(topicName, partitionId));
    }

    /**
     * Looks up the CommitLog for a specific partition.
     *
     * @param topicName   the topic name
     * @param partitionId the partition index
     * @return the CommitLog if it exists
     */
    public Optional<CommitLog> getPartition(String topicName, int partitionId) {
        return Optional.ofNullable(commitLogs.get(getPartitionKey(topicName, partitionId)));
    }

    /**
     * Builds the composite key for a partition.
     *
     * @param topic     the topic name
     * @param partition the partition index
     * @return "topic-partition" string key
     */
    public String getPartitionKey(String topic, int partition) {
        return topic + "-" + partition;
    }

    /**
     * Returns all CommitLogs belonging to a given topic.
     * Scans all keys and filters by topic prefix.
     *
     * @param topicName the topic name
     * @return list of CommitLogs for this topic (may be empty)
     */
    public List<CommitLog> getPartitionsForTopic(String topicName) {
        List<CommitLog> result = new ArrayList<>();
        // scan all entries and match by topic name
        for (Map.Entry<String, CommitLog> entry : commitLogs.entrySet()) {
            if (entry.getValue().getTopicName().equals(topicName)) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    /**
     * Returns an unmodifiable view of all partition commit logs.
     *
     * @return map of partitionKey -> CommitLog
     */
    public Map<String, CommitLog> getAllPartitions() {
        return Collections.unmodifiableMap(commitLogs);
    }

    /**
     * Deletes the CommitLog for a specific partition.
     *
     * @param topicName   the topic name
     * @param partitionId the partition index
     */
    public void deletePartition(String topicName, int partitionId) {
        commitLogs.remove(getPartitionKey(topicName, partitionId));
    }

    @Override
    public String toString() {
        return "PartitionManager{partitions=" + commitLogs.size() + "}";
    }
}
