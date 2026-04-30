package com.systemdesign.cache.store;

import com.systemdesign.cache.model.CacheEntry;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryCacheStore — ConcurrentHashMap-based cache store implementation.
 *
 * WHY ConcurrentHashMap instead of HashMap + synchronized?
 *   HashMap + synchronized: one big lock → only one thread can read/write at a time → slow
 *   ConcurrentHashMap: uses lock striping (locks individual buckets, not the whole map)
 *     → multiple threads can read/write different keys concurrently → fast
 *
 *   In a cache serving 100K requests/sec, this difference matters enormously.
 *
 * WIRING:
 *   AppConfig.createCacheService() creates InMemoryCacheStore → injects into CacheService.
 *   For distributed mode, NodeAwareCacheStore holds one InMemoryCacheStore per node.
 */
public class InMemoryCacheStore implements CacheStore {

    private final ConcurrentHashMap<String, CacheEntry> store;

    public InMemoryCacheStore() {
        this.store = new ConcurrentHashMap<>();
    }

    @Override
    public CacheEntry get(String key) {
        return store.get(key);
    }

    @Override
    public void put(String key, CacheEntry entry) {
        store.put(key, entry);
    }

    @Override
    public CacheEntry remove(String key) {
        return store.remove(key);
    }

    @Override
    public boolean contains(String key) {
        return store.containsKey(key);
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public void clear() {
        store.clear();
    }

    @Override
    public Set<String> getAllKeys() {
        return store.keySet();
    }
}
