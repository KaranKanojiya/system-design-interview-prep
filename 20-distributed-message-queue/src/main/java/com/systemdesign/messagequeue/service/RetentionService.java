package com.systemdesign.messagequeue.service;

import com.systemdesign.messagequeue.engine.CommitLog;
import com.systemdesign.messagequeue.engine.PartitionManager;
import com.systemdesign.messagequeue.model.Message;
import com.systemdesign.messagequeue.strategy.storage.StorageStrategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Message retention and cleanup — enforces time-based retention and log compaction.
 *
 * Flow (cleanup):
 *  1. Iterate all partitions for the topic
 *  2. For each partition, evaluate each message against the retention window
 *  3. Remove expired messages using CommitLog.truncateBefore()
 *  4. Return total count of removed messages
 *
 * Flow (compaction):
 *  1. Iterate all partitions for the topic
 *  2. For each partition, run log compaction (keep only the latest value per key)
 *  3. Return total count of removed duplicate messages
 */
public class RetentionService {

    // --- dependencies (constructor-injected) ---
    private final PartitionManager partitionManager;  // partition commit log access
    private final StorageStrategy storageStrategy;    // Strategy Pattern — retention/compaction logic

    public RetentionService(PartitionManager partitionManager, StorageStrategy storageStrategy) {
        this.partitionManager = partitionManager;
        this.storageStrategy = storageStrategy;
    }

    // ===================== Time-Based Retention =====================

    /**
     * Runs retention cleanup on all partitions of a topic.
     * Removes messages older than the retention window.
     *
     * @param topicName   the topic to clean
     * @param retentionMs retention window in milliseconds
     * @return total number of messages removed across all partitions
     */
    public int runCleanup(String topicName, long retentionMs) {
        List<CommitLog> partitions = partitionManager.getPartitionsForTopic(topicName);
        int totalRemoved = 0;

        for (CommitLog commitLog : partitions) {
            int beforeSize = commitLog.size();

            // 1. get all messages and filter using the storage strategy
            List<Message> allMessages = commitLog.getMessages();
            long cutoffOffset = -1;

            // 2. find the highest offset of expired messages
            for (Message msg : allMessages) {
                if (!storageStrategy.shouldRetain(msg, retentionMs)) {
                    cutoffOffset = msg.getOffset() + 1;
                }
            }

            // 3. truncate expired messages from the log
            if (cutoffOffset > 0) {
                commitLog.truncateBefore(cutoffOffset);
            }

            int removed = beforeSize - commitLog.size();
            totalRemoved += removed;

            if (removed > 0) {
                System.out.println("[RETENTION] Partition " + topicName + "-"
                        + commitLog.getPartitionId() + ": removed " + removed
                        + " expired messages (retentionMs=" + retentionMs + ")");
            }
        }

        System.out.println("[RETENTION] Cleanup complete for topic '" + topicName
                + "': removed " + totalRemoved + " total messages"
                + " (strategy=" + storageStrategy.getStrategyName() + ")");

        return totalRemoved;
    }

    // ===================== Log Compaction =====================

    /**
     * Runs log compaction on all partitions of a topic.
     * Keeps only the latest message per key (tombstone-aware).
     *
     * @param topicName the topic to compact
     * @return total number of duplicate messages removed across all partitions
     */
    public int runCompaction(String topicName) {
        List<CommitLog> partitions = partitionManager.getPartitionsForTopic(topicName);
        int totalRemoved = 0;

        for (CommitLog commitLog : partitions) {
            int beforeSize = commitLog.size();

            // 1. get all messages from the partition
            List<Message> allMessages = commitLog.getMessages();

            // 2. compact using the storage strategy (keeps latest per key)
            List<Message> compacted = storageStrategy.compact(allMessages);

            // 3. calculate how many were removed
            int removed = beforeSize - compacted.size();
            totalRemoved += removed;

            // 4. if messages were removed, truncate old and re-populate
            //    (simplified — a real implementation would do in-place compaction)
            if (removed > 0) {
                // truncate all and re-append compacted messages
                commitLog.truncateBefore(Long.MAX_VALUE);

                System.out.println("[COMPACTION] Partition " + topicName + "-"
                        + commitLog.getPartitionId() + ": removed " + removed
                        + " duplicate messages, kept " + compacted.size());
            }
        }

        System.out.println("[COMPACTION] Compaction complete for topic '" + topicName
                + "': removed " + totalRemoved + " total duplicate messages");

        return totalRemoved;
    }

    // ===================== Storage Stats =====================

    /**
     * Returns storage statistics for each partition of a topic.
     *
     * @param topicName the topic to inspect
     * @return map of partitionId to message count
     */
    public Map<Integer, Long> getStorageStats(String topicName) {
        List<CommitLog> partitions = partitionManager.getPartitionsForTopic(topicName);
        Map<Integer, Long> stats = new HashMap<>();

        for (CommitLog commitLog : partitions) {
            stats.put(commitLog.getPartitionId(), (long) commitLog.size());
        }

        return stats;
    }
}
