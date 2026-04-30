package com.systemdesign.cache.model;

/**
 * CacheNode — Represents a physical cache node in the distributed system.
 *
 * In a real distributed cache (Redis Cluster, Memcached), each node is a separate server
 * with its own memory, running on a different host:port. In our simulation, each node
 * is just a logical partition with its own InMemoryCacheStore.
 *
 * WIRING: AppConfig creates CacheNode instances → passes them to ConsistentHashStrategy.addNode()
 * → ConsistentHashStrategy creates VirtualNode instances for each CacheNode on the hash ring.
 *
 * WHY separate from VirtualNode?
 * One physical node maps to MANY virtual nodes on the consistent hashing ring (e.g., 150).
 * This improves key distribution. VirtualNode points back to its CacheNode so we can
 * route requests to the right physical server.
 */
public class CacheNode {

    private final String nodeId;    // e.g., "node-1", "cache-east-01"
    private final String host;      // e.g., "192.168.1.10"
    private final int port;         // e.g., 6379 (Redis default)
    private boolean isHealthy;      // health check status — can go down at runtime
    private int assignedKeys;       // count of keys currently stored on this node

    public CacheNode(String nodeId, String host, int port) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.isHealthy = true;  // nodes start healthy
        this.assignedKeys = 0;
    }

    // --- Getters & Setters ---

    public String getNodeId() { return nodeId; }
    public String getHost() { return host; }
    public int getPort() { return port; }

    public boolean isHealthy() { return isHealthy; }
    public void setHealthy(boolean healthy) { this.isHealthy = healthy; }

    public int getAssignedKeys() { return assignedKeys; }
    public void incrementAssignedKeys() { this.assignedKeys++; }
    public void decrementAssignedKeys() { if (this.assignedKeys > 0) this.assignedKeys--; }
    public void setAssignedKeys(int count) { this.assignedKeys = count; }

    /**
     * Display-friendly toString — used in demo output.
     * Shows the node ID, address, health status, and key count.
     */
    @Override
    public String toString() {
        return String.format("CacheNode{id='%s', address=%s:%d, healthy=%s, keys=%d}",
                nodeId, host, port, isHealthy, assignedKeys);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheNode cacheNode = (CacheNode) o;
        return nodeId.equals(cacheNode.nodeId);
    }

    @Override
    public int hashCode() {
        return nodeId.hashCode();
    }
}
