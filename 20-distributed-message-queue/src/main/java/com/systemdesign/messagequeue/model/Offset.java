package com.systemdesign.messagequeue.model;

import java.time.Instant;

/**
 * Tracks the committed offset for a (consumer group, topic, partition) tuple.
 * The broker persists these so consumers can resume after restarts.
 */
public class Offset {

    // --- identity (composite key: groupId + topicName + partitionId) ---
    private final String groupId;      // consumer group
    private final String topicName;    // topic
    private final int partitionId;     // partition index

    // --- state ---
    private long committedOffset;      // last committed offset
    private Instant lastCommitTime;    // timestamp of the last commit

    public Offset(String groupId, String topicName, int partitionId) {
        this.groupId = groupId;
        this.topicName = topicName;
        this.partitionId = partitionId;
        this.committedOffset = -1;              // no offset committed yet
        this.lastCommitTime = Instant.now();
    }

    // --- getters ---

    public String getGroupId() { return groupId; }
    public String getTopicName() { return topicName; }
    public int getPartitionId() { return partitionId; }
    public long getCommittedOffset() { return committedOffset; }
    public Instant getLastCommitTime() { return lastCommitTime; }

    // --- commit ---

    /** Advances the committed offset and updates the commit timestamp. */
    public void commit(long newOffset) {
        this.committedOffset = newOffset;
        this.lastCommitTime = Instant.now();
    }

    @Override
    public String toString() {
        return "Offset{group='" + groupId + "', topic='" + topicName
                + "', partition=" + partitionId + ", offset=" + committedOffset + "}";
    }
}
