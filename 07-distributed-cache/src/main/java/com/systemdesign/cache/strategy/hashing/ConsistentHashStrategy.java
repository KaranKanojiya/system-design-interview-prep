package com.systemdesign.cache.strategy.hashing;

import com.systemdesign.cache.model.CacheNode;
import com.systemdesign.cache.model.VirtualNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ConsistentHashStrategy — Consistent hashing with virtual nodes using a TreeMap ring.
 *
 * THE PROBLEM WITH MOD HASHING:
 *   node = hash(key) % numNodes
 *   When you add/remove a node, numNodes changes, and EVERY key maps to a different node.
 *   With 100 nodes and 1M keys: adding 1 node remaps ~990K keys. Disaster.
 *
 * CONSISTENT HASHING SOLUTION:
 *   - Imagine a ring of integers [0, 2^31).
 *   - Place each node at a position on the ring: hash(node.id) → position.
 *   - To find which node handles a key: hash(key), walk clockwise, first node you hit.
 *   - When a node is added: it only takes keys from its clockwise neighbor. ~1/N keys move.
 *   - When a node is removed: its keys move to the next clockwise node. ~1/N keys move.
 *
 * VIRTUAL NODES:
 *   With just N physical nodes on a ring, distribution can be very uneven.
 *   Solution: place each physical node at K positions (virtual nodes).
 *   With 150 virtual nodes per physical node and 3 physical nodes → 450 points on the ring.
 *   Much more uniform distribution.
 *
 * IMPLEMENTATION:
 *   TreeMap<Integer, VirtualNode> ring
 *     - Key: hash position on the ring
 *     - Value: VirtualNode (which points back to the physical CacheNode)
 *     - ceilingEntry(hash): finds the first entry >= hash → clockwise lookup → O(log n)
 *     - firstEntry(): wraps around to the smallest hash if we go past the end
 *
 * WHY MD5 for hashing?
 *   Java's String.hashCode() has poor distribution (clustering issues).
 *   MD5 gives uniform distribution across the ring. We only use 4 bytes of the 16-byte MD5 digest.
 *   (In production, you'd use MurmurHash3 or xxHash for speed. MD5 is fine for interviews.)
 *
 * WIRING: AppConfig creates ConsistentHashStrategy(numVirtualNodes)
 *   → AppConfig calls addNode() for each CacheNode
 *   → NodeAwareCacheStore calls getNode(key, nodes) to route requests
 */
public class ConsistentHashStrategy implements HashingStrategy {

    private final TreeMap<Integer, VirtualNode> ring;       // the hash ring
    private final int numVirtualNodes;                       // virtual nodes per physical node
    private final List<CacheNode> physicalNodes;             // all physical nodes (for reference)

    public ConsistentHashStrategy(int numVirtualNodes) {
        this.ring = new TreeMap<>();
        this.numVirtualNodes = numVirtualNodes;
        this.physicalNodes = new ArrayList<>();
    }

    /**
     * Find which node should handle the given key.
     *
     * Steps:
     *   1. Hash the key → position on the ring
     *   2. ceilingEntry(position) → find the first virtual node at or after this position
     *   3. If null (we're past the last position) → wrap around → firstEntry()
     *   4. Return the physical node that the virtual node points to
     *
     * Time: O(log V) where V = total virtual nodes on the ring.
     *       With 150 vnodes × 10 physical nodes = 1500 entries → log₂(1500) ≈ 11 steps.
     */
    @Override
    public CacheNode getNode(String key, List<CacheNode> nodes) {
        if (ring.isEmpty()) {
            throw new IllegalStateException("No nodes on the hash ring. Add nodes before routing keys.");
        }

        int hash = hash(key);

        // ceilingEntry: first entry with key >= hash (clockwise search)
        Map.Entry<Integer, VirtualNode> entry = ring.ceilingEntry(hash);

        // If null, wrap around to the beginning of the ring
        if (entry == null) {
            entry = ring.firstEntry();
        }

        CacheNode targetNode = entry.getValue().getPhysicalNode();

        // Skip unhealthy nodes — keep walking clockwise until we find a healthy one
        if (!targetNode.isHealthy()) {
            targetNode = findNextHealthyNode(hash);
        }

        return targetNode;
    }

    /**
     * Add a physical node to the ring.
     * Creates numVirtualNodes virtual nodes at evenly-spaced-ish positions.
     *
     * Each virtual node's position = MD5(nodeId + "-" + replicaIndex).
     * This gives deterministic, well-distributed positions.
     */
    @Override
    public void addNode(CacheNode node) {
        physicalNodes.add(node);

        for (int i = 0; i < numVirtualNodes; i++) {
            // Create a unique string for each virtual node
            String virtualNodeKey = node.getNodeId() + "-vnode-" + i;
            int hash = hash(virtualNodeKey);
            VirtualNode vnode = new VirtualNode(node, i, hash);
            ring.put(hash, vnode);
        }

        System.out.printf("  [ConsistentHash] Added node '%s' with %d virtual nodes. Ring size: %d%n",
                node.getNodeId(), numVirtualNodes, ring.size());
    }

    /**
     * Remove a physical node from the ring.
     * Removes all its virtual nodes. Keys that were on this node will automatically
     * map to the next clockwise node — no explicit redistribution needed!
     *
     * This is the beauty of consistent hashing: only ~1/N keys move when a node leaves.
     */
    @Override
    public void removeNode(CacheNode node) {
        physicalNodes.remove(node);

        int removed = 0;
        for (int i = 0; i < numVirtualNodes; i++) {
            String virtualNodeKey = node.getNodeId() + "-vnode-" + i;
            int hash = hash(virtualNodeKey);
            if (ring.remove(hash) != null) {
                removed++;
            }
        }

        System.out.printf("  [ConsistentHash] Removed node '%s' (%d virtual nodes removed). Ring size: %d%n",
                node.getNodeId(), removed, ring.size());
    }

    @Override
    public int getNodeCount() {
        return physicalNodes.size();
    }

    /**
     * Get the total number of virtual nodes on the ring.
     */
    public int getRingSize() {
        return ring.size();
    }

    /**
     * Show the distribution of keys across nodes (for the demo).
     * Given a list of keys, counts how many would map to each node.
     */
    public Map<String, Integer> getKeyDistribution(List<String> keys) {
        Map<String, Integer> distribution = new java.util.LinkedHashMap<>();
        for (CacheNode node : physicalNodes) {
            distribution.put(node.getNodeId(), 0);
        }

        for (String key : keys) {
            CacheNode node = getNode(key, physicalNodes);
            distribution.merge(node.getNodeId(), 1, Integer::sum);
        }

        return distribution;
    }

    // ===========================================================================================
    // MD5-based hash function.
    //
    // WHY MD5? Java's String.hashCode() is:
    //   s[0]*31^(n-1) + s[1]*31^(n-2) + ... + s[n-1]
    //   This clusters badly for similar strings ("user:1", "user:2", "user:3").
    //
    // MD5 gives uniform distribution. We take the first 4 bytes of the 16-byte digest
    // and combine them into a 32-bit integer.
    //
    // In production, you'd use MurmurHash3 (faster, non-cryptographic, great distribution).
    // MD5 is fine for an interview — it shows you understand WHY the hash matters.
    // ===========================================================================================
    private int hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));

            // Take first 4 bytes → combine into int
            // Use bitwise AND with 0xFF to treat bytes as unsigned
            return ((digest[0] & 0xFF) << 24)
                    | ((digest[1] & 0xFF) << 16)
                    | ((digest[2] & 0xFF) << 8)
                    | (digest[3] & 0xFF);
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed to be available in every JVM
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    /**
     * Walk clockwise from a given position to find the next healthy node.
     * If all nodes are unhealthy, returns the first node (circuit breaker).
     */
    private CacheNode findNextHealthyNode(int startHash) {
        // Walk the entire ring looking for a healthy node
        Map.Entry<Integer, VirtualNode> entry = ring.higherEntry(startHash);

        int attempts = 0;
        while (attempts < ring.size()) {
            if (entry == null) {
                entry = ring.firstEntry(); // wrap around
            }

            if (entry.getValue().getPhysicalNode().isHealthy()) {
                return entry.getValue().getPhysicalNode();
            }

            entry = ring.higherEntry(entry.getKey());
            attempts++;
        }

        // All nodes unhealthy — return first node as fallback
        System.out.println("  [WARNING] All nodes are unhealthy! Returning first available node.");
        return ring.firstEntry().getValue().getPhysicalNode();
    }
}
