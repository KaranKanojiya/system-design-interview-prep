package com.systemdesign.messagequeue.strategy.partitioning;

// Strategy Pattern (GoF) — determines how messages are assigned to partitions
public interface PartitioningStrategy {

    /**
     * Assigns a partition index for the given key.
     *
     * @param key            the message key (may be null)
     * @param partitionCount total number of partitions
     * @return partition index in [0, partitionCount)
     */
    int assignPartition(String key, int partitionCount);

    /** Returns a human-readable name for this strategy. */
    String getStrategyName();
}
