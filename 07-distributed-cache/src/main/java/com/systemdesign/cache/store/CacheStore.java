package com.systemdesign.cache.store;

import com.systemdesign.cache.model.CacheEntry;

import java.util.Set;

/**
 * CacheStore — Interface for cache storage.
 *
 * WHAT it abstracts:
 *   The physical storage of cache entries. Implementations can be:
 *   - InMemoryCacheStore: single-node, ConcurrentHashMap-backed
 *   - NodeAwareCacheStore: distributed, routes to per-node stores via consistent hashing
 *   - (Hypothetical) DiskCacheStore, HybridCacheStore, etc.
 *
 * WHY separate from CacheService?
 *   CacheService handles BUSINESS LOGIC: eviction, stats, TTL checking.
 *   CacheStore handles DATA STORAGE: get/put/remove from the backing data structure.
 *   Separation of concerns → CacheService doesn't care HOW data is stored.
 *
 * WIRING:
 *   AppConfig creates InMemoryCacheStore or NodeAwareCacheStore
 *   → injects into CacheService constructor
 *   → CacheService calls store.get(key), store.put(key, entry), etc.
 */
public interface CacheStore {

    /**
     * Retrieve a cache entry by key.
     * @return the CacheEntry, or null if not found
     */
    CacheEntry get(String key);

    /**
     * Store a cache entry.
     */
    void put(String key, CacheEntry entry);

    /**
     * Remove a cache entry by key.
     * @return the removed entry, or null if not found
     */
    CacheEntry remove(String key);

    /**
     * Check if a key exists in the store.
     */
    boolean contains(String key);

    /**
     * Number of entries currently in the store.
     */
    int size();

    /**
     * Remove all entries.
     */
    void clear();

    /**
     * Get all keys currently in the store (for iteration/cleanup).
     */
    Set<String> getAllKeys();
}
