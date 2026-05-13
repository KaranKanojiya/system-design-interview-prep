package com.systemdesign.messagequeue.strategy.partitioning;

/**
 * Hash-based partitioning — assigns partitions using the hash of the message key.
 * Messages with the same key always land in the same partition, guaranteeing
 * per-key ordering (analogous to Kafka's default partitioner).
 */
// wiring: Murmur-style hash via Math.abs(key.hashCode()) % partitionCount; null key → partition 0
public class HashPartitioningStrategy implements PartitioningStrategy {

    @Override
    public int assignPartition(String key, int partitionCount) {
        if (key == null) {
            return 0;
        }
        return Math.abs(key.hashCode()) % partitionCount;
    }

    @Override
    public String getStrategyName() {
        return "HashPartitioning";
    }
}
