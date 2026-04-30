package com.systemdesign.cache.repository;

/**
 * CacheRepository — Interface for the backing persistent store.
 *
 * WHAT it abstracts:
 *   In a real system, the cache sits IN FRONT of a slower data store (database, API, file system).
 *   When a cache miss occurs, the system fetches from the backing store and populates the cache.
 *   CacheRepository represents that backing store.
 *
 * WHY separate from CacheStore?
 *   CacheStore = fast, in-memory, volatile (data lost on restart)
 *   CacheRepository = slow, persistent, durable (data survives restart)
 *
 *   The typical flow:
 *     1. Check cache (CacheStore) → hit? return immediately
 *     2. Cache miss → fetch from backing store (CacheRepository)
 *     3. Populate cache with the fetched data
 *     4. Return to caller
 *
 * WIRING:
 *   AppConfig creates InMemoryCacheRepository
 *   → Could be injected into CacheService for "read-through" caching
 *   → In our demo, we simulate the backing store separately
 */
public interface CacheRepository {

    /**
     * Persist a value to the backing store.
     */
    void save(String key, Object value);

    /**
     * Retrieve a value from the backing store.
     * @return the value, or null if not found
     */
    Object findByKey(String key);

    /**
     * Delete a value from the backing store.
     * @return true if the key existed and was deleted
     */
    boolean delete(String key);

    /**
     * Check if a key exists in the backing store.
     */
    boolean existsByKey(String key);
}
