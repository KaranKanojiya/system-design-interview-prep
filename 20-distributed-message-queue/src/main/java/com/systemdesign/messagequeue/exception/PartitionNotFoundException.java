package com.systemdesign.messagequeue.exception;

/**
 * Thrown when a referenced partition does not exist for a given topic.
 *
 * Flow: PartitionManager.getPartition() → empty → PartitionNotFoundException
 */
public class PartitionNotFoundException extends MessageQueueException {

    private final String topicName;  // the owning topic
    private final int partitionId;   // the missing partition index

    public PartitionNotFoundException(String topicName, int partitionId) {
        super("Partition not found: " + topicName + "-" + partitionId);
        this.topicName = topicName;
        this.partitionId = partitionId;
    }

    public String getTopicName() {
        return topicName;
    }

    public int getPartitionId() {
        return partitionId;
    }

    @Override
    public String getMessage() {
        return "Partition not found: " + topicName + "-" + partitionId;
    }
}
