package com.systemdesign.cache.store;

import com.systemdesign.cache.model.CacheEntry;
import com.systemdesign.cache.model.CacheNode;
import com.systemdesign.cache.strategy.hashing.HashingStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NodeAwareCacheStore — Distributed cache store that routes to the correct node via consistent hashing.
 *
 * HOW IT WORKS:
 *   Each physical CacheNode gets its own InMemoryCacheStore.
 *   When a get/put comes in, we use the HashingStrategy to determine which node owns the key,
 *   then delegate to that node's store.
 *
 *   get("user:42") → hashingStrategy.getNode("user:42", nodes) → "cache-2"
 *                   → nodeStores.get("cache-2").get("user:42")
 *
 * WIRING:
 *   AppConfig.createDistributedCacheService() creates NodeAwareCacheStore
 *   → passes ConsistentHashStrategy and a list of CacheNodes
 *   → NodeAwareCacheStore creates one InMemoryCacheStore per node
 *   → CacheService calls store.get/put without knowing about the distributed routing
 */
public class NodeAwareCacheStore implements CacheStore {

    // One store per physical node: nodeId → InMemoryCacheStore
    private final Map<String, InMemoryCacheStore> nodeStores;

    // The hashing strategy that decides which node owns a key
    private final HashingStrategy hashingStrategy;

    // All physical nodes (passed to hashingStrategy.getNode())
    private final List<CacheNode> nodes;

    public NodeAwareCacheStore(HashingStrategy hashingStrategy, List<CacheNode> nodes) {
        this.hashingStrategy = hashingStrategy;
        this.nodes = new ArrayList<>(nodes);
        this.nodeStores = new HashMap<>();

        // Create a store for each node
        for (CacheNode node : nodes) {
            nodeStores.put(node.getNodeId(), new InMemoryCacheStore());
        }
    }

    /**
     * Route the get to the correct node's store.
     */
    @Override
    public CacheEntry get(String key) {
        CacheNode targetNode = hashingStrategy.getNode(key, nodes);
        InMemoryCacheStore store = nodeStores.get(targetNode.getNodeId());
        return store != null ? store.get(key) : null;
    }

    /**
     * Route the put to the correct node's store and update key count.
     */
    @Override
    public void put(String key, CacheEntry entry) {
        CacheNode targetNode = hashingStrategy.getNode(key, nodes);
        InMemoryCacheStore store = nodeStores.get(targetNode.getNodeId());
        if (store != null) {
            boolean isNew = !store.contains(key);
            store.put(key, entry);
            if (isNew) {
                targetNode.incrementAssignedKeys();
            }
        }
    }

    @Override
    public CacheEntry remove(String key) {
        CacheNode targetNode = hashingStrategy.getNode(key, nodes);
        InMemoryCacheStore store = nodeStores.get(targetNode.getNodeId());
        if (store != null) {
            CacheEntry removed = store.remove(key);
            if (removed != null) {
                targetNode.decrementAssignedKeys();
            }
            return removed;
        }
        return null;
    }

    @Override
    public boolean contains(String key) {
        CacheNode targetNode = hashingStrategy.getNode(key, nodes);
        InMemoryCacheStore store = nodeStores.get(targetNode.getNodeId());
        return store != null && store.contains(key);
    }

    /**
     * Total size across all nodes.
     */
    @Override
    public int size() {
        return nodeStores.values().stream()
                .mapToInt(InMemoryCacheStore::size)
                .sum();
    }

    @Override
    public void clear() {
        nodeStores.values().forEach(InMemoryCacheStore::clear);
        nodes.forEach(node -> node.setAssignedKeys(0));
    }

    @Override
    public Set<String> getAllKeys() {
        Set<String> allKeys = new HashSet<>();
        nodeStores.values().forEach(store -> allKeys.addAll(store.getAllKeys()));
        return allKeys;
    }

    /**
     * Get key distribution across nodes (for display).
     * Returns nodeId → number of keys stored on that node.
     */
    public Map<String, Integer> getNodeKeyDistribution() {
        Map<String, Integer> distribution = new HashMap<>();
        for (Map.Entry<String, InMemoryCacheStore> entry : nodeStores.entrySet()) {
            distribution.put(entry.getKey(), entry.getValue().size());
        }
        return distribution;
    }

    /**
     * Add a new node to the distributed store.
     * Creates a new InMemoryCacheStore for the node and updates the hashing strategy.
     */
    public void addNode(CacheNode node) {
        nodes.add(node);
        nodeStores.put(node.getNodeId(), new InMemoryCacheStore());
        hashingStrategy.addNode(node);
    }

    /**
     * Remove a node from the distributed store.
     * The keys on this node become orphaned — in a real system, they'd need to be migrated.
     */
    public void removeNode(CacheNode node) {
        nodes.remove(node);
        nodeStores.remove(node.getNodeId());
        hashingStrategy.removeNode(node);
    }

    public List<CacheNode> getNodes() {
        return new ArrayList<>(nodes);
    }
}
