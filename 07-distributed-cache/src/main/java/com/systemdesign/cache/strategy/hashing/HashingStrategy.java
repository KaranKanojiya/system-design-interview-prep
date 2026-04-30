package com.systemdesign.cache.strategy.hashing;

import com.systemdesign.cache.model.CacheNode;

import java.util.List;

/**
 * HashingStrategy — Interface that abstracts how keys are mapped to cache nodes.
 *
 * WHAT it abstracts:
 *   Given a key and a set of nodes, WHICH node should store this key?
 *   Different algorithms (consistent hashing, mod hashing) answer this differently.
 *
 * WHY an interface?
 *   Without this interface, NodeAwareCacheStore would have embedded hashing logic:
 *
 *     // UGLY: hashing logic hardcoded in the store
 *     public CacheNode getNodeForKey(String key) {
 *         if (hashingType.equals("mod")) {
 *             return nodes.get(Math.abs(key.hashCode()) % nodes.size());
 *         } else if (hashingType.equals("consistent")) {
 *             // 40 lines of consistent hashing with virtual nodes...
 *         }
 *     }
 *
 *   With this interface:
 *     CacheNode node = hashingStrategy.getNode(key, nodes);
 *     // One line. Strategy pattern handles the rest.
 *
 * WIRING:
 *   AppConfig creates ConsistentHashStrategy or ModHashStrategy
 *   → injects into NodeAwareCacheStore
 *   → NodeAwareCacheStore calls hashingStrategy.getNode(key, nodes) to route get/put
 */
public interface HashingStrategy {

    /**
     * Determine which node should handle the given key.
     * @param key    the cache key to route
     * @param nodes  the list of available physical nodes
     * @return the CacheNode that should store this key
     */
    CacheNode getNode(String key, List<CacheNode> nodes);

    /**
     * Notify the strategy that a new node has been added.
     * Consistent hashing: creates virtual nodes on the ring.
     * Mod hashing: no-op (but all keys need to be remapped — which is the problem).
     */
    void addNode(CacheNode node);

    /**
     * Notify the strategy that a node has been removed.
     * Consistent hashing: removes virtual nodes from the ring (only ~1/N keys move).
     * Mod hashing: ALL keys need to be remapped (this is why mod hashing is bad).
     */
    void removeNode(CacheNode node);

    /**
     * How many physical nodes does this strategy know about?
     */
    int getNodeCount();
}
