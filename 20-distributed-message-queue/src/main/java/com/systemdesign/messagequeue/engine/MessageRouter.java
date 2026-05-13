package com.systemdesign.messagequeue.engine;

import com.systemdesign.messagequeue.model.ProducerRecord;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes messages to partitions using one of three strategies:
 *
 *  1. Explicit partition — producer specifies the target partition directly
 *  2. Key-based hashing — consistent partition assignment via key.hashCode() % partitionCount
 *  3. Round-robin — even distribution when no key is provided
 *
 * This mirrors Kafka's DefaultPartitioner behavior.
 *
 * Flow:
 *  1. Producer creates a ProducerRecord (may include key and/or partition)
 *  2. MessageRouter.routeToPartition() determines the target partition
 *  3. Message is forwarded to the CommitLog for that partition
 */
public class MessageRouter {

    // --- configuration ---
    private int defaultPartitionCount;

    // --- round-robin state ---
    private final AtomicInteger roundRobinCounter;  // incremented on each keyless, partitionless send

    public MessageRouter() {
        this(3);
    }

    public MessageRouter(int defaultPartitionCount) {
        this.defaultPartitionCount = defaultPartitionCount;
        this.roundRobinCounter = new AtomicInteger(0);
    }

    // ===================== Routing =====================

    /**
     * Determines which partition a record should be written to.
     *
     * Strategy selection:
     *  1. If record has an explicit partition set (>= 0), use it directly
     *  2. If record has a key, hash it for consistent partition assignment
     *  3. Otherwise, round-robin across all partitions
     *
     * @param record         the producer record to route
     * @param partitionCount the number of partitions for the target topic
     * @return the target partition index
     */
    public int routeToPartition(ProducerRecord record, int partitionCount) {
        // strategy 1: explicit partition
        if (record.getPartition() >= 0) {
            int partition = record.getPartition();
            System.out.println("[ROUTER] Explicit partition " + partition
                    + " for topic '" + record.getTopic() + "'");
            return partition;
        }

        // strategy 2: key-based consistent hashing
        if (record.getKey() != null) {
            int partition = Math.abs(record.getKey().hashCode()) % partitionCount;
            System.out.println("[ROUTER] Key '" + record.getKey() + "' hashed to partition "
                    + partition + " for topic '" + record.getTopic() + "'");
            return partition;
        }

        // strategy 3: round-robin
        int partition = Math.abs(roundRobinCounter.getAndIncrement()) % partitionCount;
        System.out.println("[ROUTER] Round-robin assigned partition " + partition
                + " for topic '" + record.getTopic() + "'");
        return partition;
    }

    // --- configuration ---

    /** Updates the default partition count. */
    public void setPartitionCount(int count) {
        this.defaultPartitionCount = count;
    }

    public int getDefaultPartitionCount() {
        return defaultPartitionCount;
    }

    @Override
    public String toString() {
        return "MessageRouter{defaultPartitionCount=" + defaultPartitionCount
                + ", roundRobinCounter=" + roundRobinCounter.get() + "}";
    }
}
