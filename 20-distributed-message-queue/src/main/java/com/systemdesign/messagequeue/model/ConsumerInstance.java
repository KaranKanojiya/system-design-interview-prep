package com.systemdesign.messagequeue.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A single consumer within a consumer group.
 * Tracks heartbeat liveness and partition assignments.
 */
public class ConsumerInstance {

    // --- identity ---
    private final String consumerId;                           // unique consumer ID
    private final String groupId;                              // owning consumer group
    private final String host;                                 // host/IP the consumer runs on

    // --- liveness ---
    private Instant lastHeartbeat;                             // last time this consumer sent a heartbeat

    // --- assignments ---
    private List<PartitionAssignment> assignedPartitions;      // partitions currently assigned

    public ConsumerInstance(String consumerId, String groupId, String host) {
        this.consumerId = consumerId;
        this.groupId = groupId;
        this.host = host;
        this.lastHeartbeat = Instant.now();
        this.assignedPartitions = new ArrayList<>();
    }

    // --- getters ---

    public String getConsumerId() { return consumerId; }
    public String getGroupId() { return groupId; }
    public String getHost() { return host; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public List<PartitionAssignment> getAssignedPartitions() { return assignedPartitions; }

    // --- setters ---

    public void setAssignedPartitions(List<PartitionAssignment> assignedPartitions) {
        this.assignedPartitions = new ArrayList<>(assignedPartitions);
    }

    // --- heartbeat ---

    /** Updates the heartbeat timestamp to now. Called on each heartbeat RPC. */
    public void updateHeartbeat() {
        this.lastHeartbeat = Instant.now();
    }

    /** Returns true if the consumer is alive (heartbeat within the given timeout). */
    public boolean isAlive(Duration timeout) {
        return Duration.between(lastHeartbeat, Instant.now()).compareTo(timeout) < 0;
    }

    @Override
    public String toString() {
        return "ConsumerInstance{consumerId='" + consumerId + "', groupId='" + groupId
                + "', host='" + host + "', partitions=" + assignedPartitions.size() + "}";
    }
}
