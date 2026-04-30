package com.systemdesign.cache.repository;

import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryCacheRepository — ConcurrentHashMap-based backing store implementation.
 *
 * In a real system, this would be replaced by:
 *   - JdbcCacheRepository (talks to PostgreSQL/MySQL)
 *   - MongoCacheRepository (talks to MongoDB)
 *   - S3CacheRepository (talks to object storage)
 *
 * We use ConcurrentHashMap here to simulate a persistent store.
 * In the demo, this represents "the database" that the cache protects.
 *
 * WIRING: AppConfig creates InMemoryCacheRepository → available as the "source of truth."
 */
public class InMemoryCacheRepository implements CacheRepository {

    // Simulates a database table: key → value
    private final ConcurrentHashMap<String, Object> database;

    public InMemoryCacheRepository() {
        this.database = new ConcurrentHashMap<>();
    }

    @Override
    public void save(String key, Object value) {
        database.put(key, value);
    }

    @Override
    public Object findByKey(String key) {
        // Simulate database latency (in a real system, this would be 1-50ms)
        // We just print to show that the backing store was hit.
        Object value = database.get(key);
        if (value != null) {
            System.out.printf("    [Repository] DB HIT for key '%s' (simulated slow fetch)%n", key);
        } else {
            System.out.printf("    [Repository] DB MISS for key '%s' (not in backing store)%n", key);
        }
        return value;
    }

    @Override
    public boolean delete(String key) {
        return database.remove(key) != null;
    }

    @Override
    public boolean existsByKey(String key) {
        return database.containsKey(key);
    }

    /**
     * Seed the backing store with data (used in demos).
     */
    public void seed(String key, Object value) {
        database.put(key, value);
    }

    public int size() {
        return database.size();
    }
}
