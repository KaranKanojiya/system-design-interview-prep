package com.systemdesign.cache.strategy.eviction;

import com.systemdesign.cache.model.CacheEntry;

/**
 * EvictionStrategy — Interface that abstracts the cache eviction algorithm.
 *
 * WHAT it abstracts:
 *   The decision of WHICH key to remove when the cache is full.
 *   Different algorithms (LRU, LFU, TTL) make this decision differently.
 *
 * WHY an interface?
 *   Without this interface, CacheService would need ugly if-else chains:
 *
 *     // UGLY: policy logic embedded directly in CacheService
 *     public String evictOne() {
 *         if (config.getEvictionPolicy() == EvictionPolicy.LRU) {
 *             // find the least recently used key using linked list...
 *             // 30 lines of LRU-specific code here
 *         } else if (config.getEvictionPolicy() == EvictionPolicy.LFU) {
 *             // find the least frequently used key using frequency map...
 *             // 40 lines of LFU-specific code here
 *         } else if (config.getEvictionPolicy() == EvictionPolicy.TTL) {
 *             // find the oldest expired key using TreeMap...
 *             // 25 lines of TTL-specific code here
 *         }
 *         // Adding a new policy means modifying this method — violates Open/Closed Principle
 *     }
 *
 *   With this interface, CacheService just calls:
 *     String keyToEvict = evictionStrategy.evict();
 *     // One line. Polymorphism routes to the right implementation.
 *     // Adding a new policy? Just implement this interface. CacheService stays unchanged.
 *
 * WIRING:
 *   AppConfig creates concrete implementations (LRUEvictionStrategy, LFUEvictionStrategy, etc.)
 *   → injects one into CacheService via constructor
 *   → CacheService calls evictionStrategy.onGet/onPut/evict without knowing which impl it has
 */
public interface EvictionStrategy {

    /**
     * Called when a key is accessed (read).
     * LRU: moves the key to the head of the recency list
     * LFU: increments the key's frequency counter
     * TTL: no-op (access doesn't affect expiration)
     */
    void onGet(String key);

    /**
     * Called when a key is inserted (write).
     * All strategies: register the key in their tracking data structure.
     */
    void onPut(String key, CacheEntry entry);

    /**
     * Determine which key to evict and remove it from tracking.
     * Returns the key that should be evicted, or null if the strategy is empty.
     *
     * LRU: returns the tail of the recency list (least recently used)
     * LFU: returns the first key in the lowest-frequency bucket
     * TTL: returns the key with the earliest expiration time
     */
    String evict();

    /**
     * Remove a key from the eviction tracking (called on explicit delete).
     * Without this, deleted keys would remain in the eviction data structure
     * and could be "evicted" even though they're already gone.
     */
    void remove(String key);

    /**
     * How many keys is this strategy currently tracking?
     */
    int size();

    /**
     * Human-readable name for display/logging.
     */
    String getEvictionPolicyName();
}
