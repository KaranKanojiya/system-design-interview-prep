package com.systemdesign.messagequeue.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A single partition within a topic — the unit of parallelism and ordering.
 * Tracks which broker is the leader and which replicas are in-sync (ISR).
 */
public class Partition {

    // --- identity ---
    private final String topicName;               // owning topic
    private final int partitionId;                // partition index within the topic

    // --- replication ---
    private String leaderId;                      // broker ID of the current leader
    private final List<String> replicaIds;        // all replica broker IDs (includes leader)
    private final List<String> inSyncReplicaIds;  // ISR — replicas caught up with the leader

    public Partition(String topicName, int partitionId, String leaderId, List<String> replicaIds) {
        this(topicName, partitionId, leaderId, replicaIds, replicaIds);
    }

    public Partition(String topicName, int partitionId, String leaderId,
                     List<String> replicaIds, List<String> inSyncReplicaIds) {
        this.topicName = topicName;
        this.partitionId = partitionId;
        this.leaderId = leaderId;
        this.replicaIds = new ArrayList<>(replicaIds);
        this.inSyncReplicaIds = new ArrayList<>(inSyncReplicaIds);
    }

    // --- getters ---

    public String getTopicName() { return topicName; }
    public int getPartitionId() { return partitionId; }
    public String getLeaderId() { return leaderId; }
    public List<String> getReplicaIds() { return replicaIds; }
    public List<String> getInSyncReplicaIds() { return inSyncReplicaIds; }

    // --- setters ---

    public void setLeaderId(String leaderId) { this.leaderId = leaderId; }

    // --- ISR management ---

    /** Returns true if the given broker is the leader for this partition. */
    public boolean isLeader(String brokerId) {
        return leaderId.equals(brokerId);
    }

    /** Returns true if the given broker is in the ISR. */
    public boolean isInSync(String brokerId) {
        return inSyncReplicaIds.contains(brokerId);
    }

    /** Adds a broker to the ISR (e.g., after it catches up). */
    public void addToIsr(String brokerId) {
        if (!inSyncReplicaIds.contains(brokerId)) {
            inSyncReplicaIds.add(brokerId);
        }
    }

    /** Removes a broker from the ISR (e.g., after it falls behind or dies). */
    public void removeFromIsr(String brokerId) {
        inSyncReplicaIds.remove(brokerId);
    }

    /** Composite key used for partition identification across the cluster. */
    public String getPartitionKey() {
        return topicName + "-" + partitionId;
    }

    @Override
    public String toString() {
        return "Partition{topic='" + topicName + "', id=" + partitionId
                + ", leader='" + leaderId + "', isr=" + inSyncReplicaIds + "}";
    }
}
