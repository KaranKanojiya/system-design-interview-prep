package com.systemdesign.messagequeue.engine;

import com.systemdesign.messagequeue.model.AckMode;
import com.systemdesign.messagequeue.model.Message;
import com.systemdesign.messagequeue.model.Partition;

import java.util.List;

/**
 * Simulates partition replication across brokers.
 *
 * In a real distributed system, replication ensures durability by copying each message
 * to multiple brokers. The ack mode controls the trade-off between latency and durability:
 *
 *  - NONE (acks=0)   — fire-and-forget, no acknowledgment
 *  - LEADER (acks=1) — leader acknowledges, replicas async
 *  - ALL (acks=-1)    — all ISR replicas must acknowledge
 *
 * Flow:
 *  1. Producer sends message with a chosen AckMode
 *  2. Leader receives the message and writes to its local log
 *  3. Based on AckMode, ReplicationEngine decides when to acknowledge
 *  4. For ALL mode, each ISR replica must confirm before ack is sent back
 */
public class ReplicationEngine {

    // --- configuration ---
    private final int replicationFactor;  // desired number of replicas per partition

    public ReplicationEngine(int replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    // ===================== Replication =====================

    /**
     * Simulates replication of a message to the partition's replicas.
     *
     * @param message   the message being replicated
     * @param partition the target partition (contains ISR info)
     * @param ackMode   the acknowledgment mode requested by the producer
     * @return true if replication succeeded per the ack mode's requirements
     */
    public boolean replicate(Message message, Partition partition, AckMode ackMode) {
        String partitionKey = partition.getPartitionKey();

        switch (ackMode) {
            case NONE -> {
                // fire-and-forget — no acknowledgment required
                System.out.println("[REPLICATION] acks=NONE for " + partitionKey
                        + " — no acknowledgment, returning immediately");
                return true;
            }
            case LEADER -> {
                // only the leader needs to acknowledge
                System.out.println("[REPLICATION] acks=LEADER for " + partitionKey
                        + " — leader '" + partition.getLeaderId() + "' acknowledged message offset="
                        + message.getOffset());
                return true;
            }
            case ALL -> {
                // all ISR replicas must acknowledge
                return replicateToAllIsr(message, partition);
            }
            default -> {
                System.out.println("[REPLICATION] Unknown ack mode: " + ackMode);
                return false;
            }
        }
    }

    /**
     * Returns the number of replicas assigned to the given partition.
     *
     * @param partition the partition to check
     * @return the total replica count
     */
    public int getReplicaCount(Partition partition) {
        return partition.getReplicaIds().size();
    }

    // ===================== Internal =====================

    /**
     * Simulates replication to all ISR replicas for acks=ALL mode.
     * Checks that ISR size meets the replication factor; logs a warning if not.
     */
    private boolean replicateToAllIsr(Message message, Partition partition) {
        String partitionKey = partition.getPartitionKey();
        List<String> isr = partition.getInSyncReplicaIds();

        System.out.println("[REPLICATION] acks=ALL for " + partitionKey
                + " — replicating to " + isr.size() + " ISR replicas");

        // warn if ISR is smaller than the replication factor
        if (isr.size() < replicationFactor) {
            System.out.println("[REPLICATION] WARNING: ISR size (" + isr.size()
                    + ") < replicationFactor (" + replicationFactor
                    + ") for " + partitionKey + " — data durability at risk");
        }

        // simulate acknowledgment from each ISR replica
        for (String replicaId : isr) {
            System.out.println("[REPLICATION]   Replica '" + replicaId
                    + "' acknowledged message offset=" + message.getOffset());
        }

        System.out.println("[REPLICATION] All " + isr.size()
                + " ISR replicas acknowledged for " + partitionKey);
        return true;
    }

    // --- getters ---

    public int getReplicationFactor() { return replicationFactor; }

    @Override
    public String toString() {
        return "ReplicationEngine{replicationFactor=" + replicationFactor + "}";
    }
}
