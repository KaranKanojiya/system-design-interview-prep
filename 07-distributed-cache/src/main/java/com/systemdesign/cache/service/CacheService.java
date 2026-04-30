package com.systemdesign.cache.service;

import com.systemdesign.cache.exception.CacheFullException;
import com.systemdesign.cache.model.CacheConfig;
import com.systemdesign.cache.model.CacheEntry;
import com.systemdesign.cache.model.CacheStats;
import com.systemdesign.cache.store.CacheStore;
import com.systemdesign.cache.strategy.eviction.EvictionStrategy;

/**
 * CacheService — FACADE — The main entry point for all cache operations.
 *
 * WHAT IS THE FACADE PATTERN?
 *   A facade provides a simplified interface to a complex subsystem.
 *   Instead of the caller juggling store + eviction + stats + TTL checking,
 *   they just call cacheService.get(key) and all the internal coordination happens here.
 *
 * WHAT THIS FACADE WRAPS:
 *   - CacheStore: the actual data storage (InMemoryCacheStore or NodeAwareCacheStore)
 *   - EvictionStrategy: decides which key to evict when the cache is full (LRU, LFU, TTL)
 *   - CacheStats: tracks hits, misses, evictions (for monitoring)
 *   - CacheConfig: max size, default TTL, etc.
 *
 * CALL CHAIN for get(key):
 *   CacheController.handleGet(key)
 *     → CacheService.get(key)
 *       → CacheStore.get(key)              // fetch from storage
 *       → if found: entry.isExpired()?      // lazy TTL check
 *         → if expired: store.remove(key), stats.recordMiss()
 *         → if valid: eviction.onGet(key), entry.touch(), stats.recordHit()
 *       → if not found: stats.recordMiss()
 *
 * CALL CHAIN for put(key, value):
 *   CacheController.handlePut(key, value, ttl)
 *     → CacheService.put(key, value, ttlSeconds)
 *       → if store is full: eviction.evict() → store.remove(evictedKey), stats.recordEviction()
 *       → CacheEntry.builder().key(key).value(value).ttlSeconds(ttl).build()
 *       → CacheStore.put(key, entry)
 *       → EvictionStrategy.onPut(key, entry)
 *       → stats.recordPut()
 *
 * WIRING: AppConfig.createCacheService() creates all dependencies → injects into CacheService.
 */
public class CacheService {

    private final CacheStore store;
    private final EvictionStrategy evictionStrategy;
    private final CacheStats stats;
    private final CacheConfig config;

    /**
     * Constructor — all dependencies injected.
     * This is manual dependency injection (no Spring, no Guice).
     * AppConfig is the ONLY class that calls this constructor.
     */
    public CacheService(CacheStore store, EvictionStrategy evictionStrategy,
                        CacheStats stats, CacheConfig config) {
        this.store = store;
        this.evictionStrategy = evictionStrategy;
        this.stats = stats;
        this.config = config;
    }

    /**
     * Get a value from the cache.
     *
     * Handles three cases:
     *   1. Key not found → cache miss → return null
     *   2. Key found but expired (lazy expiration) → remove, cache miss → return null
     *   3. Key found and valid → cache hit → return value
     */
    public Object get(String key) {
        CacheEntry entry = store.get(key);

        // Case 1: not found
        if (entry == null) {
            stats.recordMiss();
            return null;
        }

        // Case 2: found but expired (lazy TTL expiration)
        // This is how Redis does it: don't waste CPU scanning for expired keys.
        // Just check on access and clean up as you go.
        if (entry.isExpired()) {
            store.remove(key);
            evictionStrategy.remove(key);
            stats.recordMiss();
            return null;
        }

        // Case 3: found and valid — cache hit!
        entry.touch();                      // update lastAccessedAt, increment frequency
        evictionStrategy.onGet(key);        // notify eviction strategy (LRU moves to head, LFU bumps freq)
        stats.recordHit();
        return entry.getValue();
    }

    /**
     * Put a value into the cache with an explicit TTL.
     */
    public void put(String key, Object value, long ttlSeconds) {
        // If key already exists, treat as update (no need to evict)
        if (!store.contains(key)) {
            // Check if we need to evict to make room
            if (store.size() >= config.getMaxSize()) {
                evictOne();
            }
        }

        // Build the cache entry using the Builder pattern
        CacheEntry.Builder builder = CacheEntry.builder()
                .key(key)
                .value(value);

        if (ttlSeconds > 0) {
            builder.ttlSeconds(ttlSeconds);
        } else if (config.getDefaultTtlSeconds() > 0) {
            builder.ttlSeconds(config.getDefaultTtlSeconds());
        }

        CacheEntry entry = builder.build();

        store.put(key, entry);
        evictionStrategy.onPut(key, entry);
        stats.recordPut();
    }

    /**
     * Put a value with the default TTL from config.
     */
    public void put(String key, Object value) {
        put(key, value, config.getDefaultTtlSeconds());
    }

    /**
     * Explicitly delete a key from the cache.
     * @return true if the key was found and removed
     */
    public boolean delete(String key) {
        CacheEntry removed = store.remove(key);
        if (removed != null) {
            evictionStrategy.remove(key);
            return true;
        }
        return false;
    }

    /**
     * Clear the entire cache.
     */
    public void clear() {
        store.clear();
        stats.reset();
    }

    /**
     * Get cache statistics.
     */
    public CacheStats getStats() {
        return stats;
    }

    /**
     * Get the current number of entries in the cache.
     */
    public int size() {
        return store.size();
    }

    /**
     * Get the eviction strategy (for display/debugging).
     */
    public EvictionStrategy getEvictionStrategy() {
        return evictionStrategy;
    }

    /**
     * Get the underlying store (for advanced operations like node distribution).
     */
    public CacheStore getStore() {
        return store;
    }

    /**
     * Get config (for display).
     */
    public CacheConfig getConfig() {
        return config;
    }

    // ===========================================================================================
    // Private helper — evict one entry when the cache is full.
    //
    // This is where the Strategy pattern pays off. CacheService doesn't know or care
    // whether LRU, LFU, or TTL is being used. It just calls evictionStrategy.evict().
    //
    // WITHOUT strategy pattern (the ugly if-else approach):
    //   private void evictOne() {
    //       if (config.getEvictionPolicy() == EvictionPolicy.LRU) {
    //           // find least recently used key from linked list
    //           Node lru = tail.prev; String key = lru.key;
    //           removeNode(lru); map.remove(key);
    //           store.remove(key);
    //       } else if (config.getEvictionPolicy() == EvictionPolicy.LFU) {
    //           // find least frequently used key from frequency buckets
    //           LinkedHashSet<String> bucket = freqToKeys.get(minFreq);
    //           String key = bucket.iterator().next(); bucket.remove(key);
    //           keyToFreq.remove(key); store.remove(key);
    //       } else if (config.getEvictionPolicy() == EvictionPolicy.TTL) {
    //           // find earliest-expiring key from TreeMap
    //           ... more code ...
    //       }
    //   }
    //
    // WITH strategy pattern (clean):
    //   private void evictOne() {
    //       String keyToEvict = evictionStrategy.evict();
    //       store.remove(keyToEvict);
    //   }
    // ===========================================================================================
    private void evictOne() {
        String keyToEvict = evictionStrategy.evict();
        if (keyToEvict != null) {
            store.remove(keyToEvict);
            stats.recordEviction();
        } else {
            throw new CacheFullException("Cache is full and no key could be evicted. Max size: " + config.getMaxSize());
        }
    }
}
