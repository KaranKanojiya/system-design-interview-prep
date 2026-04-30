package com.systemdesign.cache.model;

/**
 * VirtualNode — A virtual node on the consistent hashing ring.
 *
 * WHY virtual nodes?
 * ------------------
 * Without virtual nodes, if you have 3 physical nodes on a ring, the key distribution
 * can be extremely uneven (one node might get 60% of keys, another 10%).
 *
 * With virtual nodes, each physical node gets ~150 positions on the ring. This smooths
 * out the distribution. When you hash a key and walk clockwise, you're much more likely
 * to land on a fairly-distributed virtual node.
 *
 * Example:
 *   Physical node "cache-1" → virtual nodes at positions 1042, 5891, 12033, ... (150 total)
 *   Physical node "cache-2" → virtual nodes at positions 892, 3401, 8877, ...  (150 total)
 *   Key "user:42" hashes to 5900 → walks clockwise → hits position 8877 → routes to cache-2
 *
 * WIRING: ConsistentHashStrategy.addNode(CacheNode) creates 150 VirtualNodes per CacheNode,
 * each at a different position on the TreeMap<Integer, VirtualNode> ring.
 */
public class VirtualNode {

    private final CacheNode physicalNode;   // the real server this virtual node maps to
    private final int replicaIndex;         // which replica (0..149) of the physical node
    private final int hash;                 // position on the hash ring (0..Integer.MAX_VALUE)

    public VirtualNode(CacheNode physicalNode, int replicaIndex, int hash) {
        this.physicalNode = physicalNode;
        this.replicaIndex = replicaIndex;
        this.hash = hash;
    }

    public CacheNode getPhysicalNode() { return physicalNode; }
    public int getReplicaIndex() { return replicaIndex; }
    public int getHash() { return hash; }

    /**
     * Display string showing which physical node this virtual node belongs to
     * and its position on the ring.
     */
    @Override
    public String toString() {
        return String.format("VirtualNode{physical='%s', replica=%d, hash=%d}",
                physicalNode.getNodeId(), replicaIndex, hash);
    }
}
