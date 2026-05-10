package com.systemdesign.scheduler.model;

import java.time.Duration;
import java.time.Instant;

// Wiring: Represents a node in the scheduler cluster.
// Participates in leader election (bully algorithm - higher priority wins).
// The leader node is responsible for task assignment and rebalancing.
public class SchedulerNode {

    private final String nodeId;
    private final String hostname;
    private boolean isLeader;
    private Instant lastHeartbeat;
    private final Instant startedAt;
    private final int priority;

    public SchedulerNode(String nodeId, String hostname, int priority) {
        this.nodeId = nodeId;
        this.hostname = hostname;
        this.priority = priority;
        this.isLeader = false;
        this.lastHeartbeat = Instant.now();
        this.startedAt = Instant.now();
    }

    // --- Heartbeat and liveness ---

    public void updateHeartbeat() {
        this.lastHeartbeat = Instant.now();
    }

    /**
     * Returns true if the last heartbeat is within the given timeout duration.
     */
    public boolean isAlive(Duration timeout) {
        return Duration.between(lastHeartbeat, Instant.now()).compareTo(timeout) < 0;
    }

    // --- Getters and setters ---

    public String getNodeId() { return nodeId; }
    public String getHostname() { return hostname; }
    public boolean isLeader() { return isLeader; }
    public void setLeader(boolean leader) { this.isLeader = leader; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public Instant getStartedAt() { return startedAt; }
    public int getPriority() { return priority; }

    @Override
    public String toString() {
        return "SchedulerNode{id='" + nodeId + "', host='" + hostname
                + "', leader=" + isLeader + ", priority=" + priority + "}";
    }
}
