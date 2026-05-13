package com.systemdesign.messagequeue.model;

import java.util.Objects;

/**
 * Assignment of a specific partition to a specific consumer.
 * Used during consumer group rebalancing to track which consumer owns which partition.
 */
public class PartitionAssignment {

    // --- identity ---
    private final String topicName;    // topic this partition belongs to
    private final int partitionId;     // partition index
    private final String consumerId;   // assigned consumer

    public PartitionAssignment(String topicName, int partitionId, String consumerId) {
        this.topicName = topicName;
        this.partitionId = partitionId;
        this.consumerId = consumerId;
    }

    // --- getters ---

    public String getTopicName() { return topicName; }
    public int getPartitionId() { return partitionId; }
    public String getConsumerId() { return consumerId; }

    // --- equals / hashCode (by topic + partition + consumer) ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PartitionAssignment that = (PartitionAssignment) o;
        return partitionId == that.partitionId
                && Objects.equals(topicName, that.topicName)
                && Objects.equals(consumerId, that.consumerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topicName, partitionId, consumerId);
    }

    @Override
    public String toString() {
        return "PartitionAssignment{topic='" + topicName + "', partition=" + partitionId
                + ", consumer='" + consumerId + "'}";
    }
}
