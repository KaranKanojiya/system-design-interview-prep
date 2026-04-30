package com.systemdesign.cache.service;

import com.systemdesign.cache.model.CacheEntry;
import com.systemdesign.cache.model.CacheNode;

import java.util.List;

/**
 * ReplicationService — Conceptual replication for distributed cache.
 *
 * In a real distributed cache (Redis Cluster, Memcached), data is replicated to
 * follower/replica nodes for fault tolerance. If the primary node dies, a replica
 * can be promoted to primary and serve requests without data loss.
 *
 * REPLICATION STRATEGIES:
 *   1. Synchronous: Write waits until all replicas confirm. Strong consistency, high latency.
 *   2. Asynchronous: Write returns immediately, replicas updated in background. Low latency, eventual consistency.
 *   3. Semi-synchronous: Write waits for at least one replica. Balance of consistency and latency.
 *
 * This implementation SIMULATES async replication with print statements.
 * In a real system, this would involve network calls to replica nodes.
 *
 * WIRING:
 *   AppConfig creates ReplicationService(replicationFactor)
 *   → CacheService calls replicationService.replicateToFollowers() after successful put()
 *   → ReplicationService simulates sending data to follower nodes
 */
public class ReplicationService {

    private final int replicationFactor;  // how many copies of each key (including primary)

    public ReplicationService(int replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    /**
     * Simulate replicating a key-value pair to follower nodes.
     *
     * In a real system, this would:
     *   1. Determine which nodes are replicas for this key (next N nodes on the consistent hash ring)
     *   2. Send the data to each replica via network RPC
     *   3. Handle failures (retry, mark node unhealthy, etc.)
     *
     * @param key          the cache key
     * @param entry        the cache entry to replicate
     * @param primaryNode  the node that owns this key
     * @param allNodes     all available nodes (to pick replicas from)
     */
    public void replicateToFollowers(String key, CacheEntry entry,
                                     CacheNode primaryNode, List<CacheNode> allNodes) {
        if (allNodes.size() <= 1) {
            // Only one node — nothing to replicate to
            return;
        }

        int replicaCount = Math.min(replicationFactor - 1, allNodes.size() - 1);

        System.out.printf("    [Replication] Replicating key '%s' from %s to %d follower(s)%n",
                key, primaryNode.getNodeId(), replicaCount);

        // In a real system, we'd pick the next N nodes clockwise on the consistent hash ring.
        // Here we simulate by picking the next N nodes in the list after the primary.
        int primaryIndex = -1;
        for (int i = 0; i < allNodes.size(); i++) {
            if (allNodes.get(i).getNodeId().equals(primaryNode.getNodeId())) {
                primaryIndex = i;
                break;
            }
        }

        for (int i = 1; i <= replicaCount; i++) {
            int replicaIndex = (primaryIndex + i) % allNodes.size();
            CacheNode replicaNode = allNodes.get(replicaIndex);

            // Simulate async replication
            simulateAsyncReplication(key, entry, replicaNode);
        }
    }

    /**
     * Simulate sending data to a replica node.
     *
     * In a real system, this would be:
     *   - An RPC call (gRPC, Thrift) to the replica node
     *   - Possibly using a write-ahead log (WAL) for durability
     *   - With retry logic and circuit breakers
     */
    private void simulateAsyncReplication(String key, CacheEntry entry, CacheNode replicaNode) {
        if (!replicaNode.isHealthy()) {
            System.out.printf("    [Replication] WARNING: Replica node '%s' is unhealthy. " +
                    "Data may be lost if primary fails!%n", replicaNode.getNodeId());
            return;
        }

        // Simulate network latency (just a print, no actual delay)
        System.out.printf("    [Replication] → Sent key '%s' to replica '%s' (async)%n",
                key, replicaNode.getNodeId());
    }

    /**
     * Simulate handling a node failure and promoting a replica.
     *
     * In a real system (Redis Sentinel / Redis Cluster):
     *   1. Detect primary failure (heartbeat timeout)
     *   2. Elect a replica as the new primary (Raft / Paxos)
     *   3. Update routing table so clients send requests to the new primary
     *   4. New primary starts accepting writes
     */
    public void handleNodeFailure(CacheNode failedNode, List<CacheNode> replicas) {
        System.out.printf("    [Replication] Node '%s' FAILED! Initiating failover...%n",
                failedNode.getNodeId());

        failedNode.setHealthy(false);

        // Find a healthy replica to promote
        for (CacheNode replica : replicas) {
            if (replica.isHealthy() && !replica.getNodeId().equals(failedNode.getNodeId())) {
                System.out.printf("    [Replication] Promoting replica '%s' to primary.%n",
                        replica.getNodeId());
                System.out.printf("    [Replication] Failover complete. Clients should now route to '%s'.%n",
                        replica.getNodeId());
                return;
            }
        }

        System.out.println("    [Replication] CRITICAL: No healthy replicas available! Data may be lost.");
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }
}
