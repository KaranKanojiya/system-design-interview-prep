package com.systemdesign.messagequeue.model;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A broker node in the distributed message queue cluster.
 * One broker is elected as the controller (handles partition assignment and leader election).
 */
public class BrokerNode {

    // --- identity ---
    private final String brokerId;                     // unique broker ID
    private final String host;                         // hostname or IP
    private final int port;                            // listening port

    // --- cluster role ---
    private boolean isController;                      // true if this broker is the cluster controller

    // --- partition leadership ---
    private final Set<String> partitionLeadership;     // set of "topic-partition" keys this broker leads

    // --- liveness ---
    private Instant lastHeartbeat;                     // last heartbeat received by the controller

    public BrokerNode(String brokerId, String host, int port) {
        this.brokerId = brokerId;
        this.host = host;
        this.port = port;
        this.isController = false;
        this.partitionLeadership = new HashSet<>();
        this.lastHeartbeat = Instant.now();
    }

    // --- getters ---

    public String getBrokerId() { return brokerId; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public boolean isController() { return isController; }
    public Set<String> getPartitionLeadership() { return partitionLeadership; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }

    // --- setters ---

    public void setController(boolean controller) { this.isController = controller; }

    // --- heartbeat ---

    /** Updates the heartbeat timestamp to now. */
    public void updateHeartbeat() {
        this.lastHeartbeat = Instant.now();
    }

    /** Returns true if the broker is alive (heartbeat within the given timeout). */
    public boolean isAlive(Duration timeout) {
        return Duration.between(lastHeartbeat, Instant.now()).compareTo(timeout) < 0;
    }

    // --- leadership management ---

    /** Records that this broker is the leader for the given topic-partition. */
    public void addLeadership(String topicPartition) {
        partitionLeadership.add(topicPartition);
    }

    /** Removes leadership for the given topic-partition (e.g., after reassignment). */
    public void removeLeadership(String topicPartition) {
        partitionLeadership.remove(topicPartition);
    }

    @Override
    public String toString() {
        return "BrokerNode{id='" + brokerId + "', host='" + host + ":" + port
                + "', controller=" + isController
                + ", leading=" + partitionLeadership.size() + " partitions}";
    }
}
