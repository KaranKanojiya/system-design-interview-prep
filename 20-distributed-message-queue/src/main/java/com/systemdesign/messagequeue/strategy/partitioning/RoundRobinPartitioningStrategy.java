package com.systemdesign.messagequeue.strategy.partitioning;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin partitioning — distributes messages evenly across partitions
 * regardless of key. Ideal for maximizing throughput when per-key ordering
 * is not required.
 */
// wiring: AtomicInteger counter incremented on each call, mod partitionCount for even spread
public class RoundRobinPartitioningStrategy implements PartitioningStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public int assignPartition(String key, int partitionCount) {
        int current = counter.getAndIncrement();
        return Math.abs(current % partitionCount);
    }

    @Override
    public String getStrategyName() {
        return "RoundRobinPartitioning";
    }
}
