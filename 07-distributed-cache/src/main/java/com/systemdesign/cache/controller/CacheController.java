package com.systemdesign.cache.controller;

import com.systemdesign.cache.display.CacheStatsDisplay;
import com.systemdesign.cache.model.CacheStats;
import com.systemdesign.cache.service.CacheService;

/**
 * CacheController — Simulated REST controller (print-based, no HTTP).
 *
 * In a real system, this would be a Spring @RestController with HTTP endpoints:
 *   GET  /cache/{key}           → handleGet(key)
 *   PUT  /cache/{key}           → handlePut(key, value, ttl)
 *   DELETE /cache/{key}         → handleDelete(key)
 *   GET  /cache/stats           → handleStats()
 *
 * Since this project uses plain Java (no frameworks), we simulate the controller
 * as a regular class with print-based "responses."
 *
 * WIRING:
 *   AppConfig creates CacheController(cacheService, statsDisplay)
 *   → DistributedCacheApp calls controller methods in demos
 *   → CacheController delegates to CacheService
 */
public class CacheController {

    private final CacheService cacheService;
    private final CacheStatsDisplay statsDisplay;

    public CacheController(CacheService cacheService, CacheStatsDisplay statsDisplay) {
        this.cacheService = cacheService;
        this.statsDisplay = statsDisplay;
    }

    /**
     * Simulate: GET /cache/{key}
     * Returns the cached value, or null on cache miss.
     */
    public Object handleGet(String key) {
        System.out.printf("  [GET /cache/%s]%n", key);
        Object value = cacheService.get(key);

        if (value != null) {
            System.out.printf("  → 200 OK: '%s' = %s%n", key, value);
        } else {
            System.out.printf("  → 404 NOT FOUND: '%s' (cache miss)%n", key);
        }

        return value;
    }

    /**
     * Simulate: PUT /cache/{key}
     */
    public void handlePut(String key, Object value, long ttlSeconds) {
        System.out.printf("  [PUT /cache/%s] value=%s, ttl=%ds%n", key, value, ttlSeconds);
        cacheService.put(key, value, ttlSeconds);
        System.out.printf("  → 201 CREATED: '%s' cached successfully%n", key);
    }

    /**
     * Simulate: PUT /cache/{key} with default TTL.
     */
    public void handlePut(String key, Object value) {
        handlePut(key, value, 0);
    }

    /**
     * Simulate: DELETE /cache/{key}
     */
    public void handleDelete(String key) {
        System.out.printf("  [DELETE /cache/%s]%n", key);
        boolean deleted = cacheService.delete(key);

        if (deleted) {
            System.out.printf("  → 200 OK: '%s' deleted from cache%n", key);
        } else {
            System.out.printf("  → 404 NOT FOUND: '%s' was not in cache%n", key);
        }
    }

    /**
     * Simulate: GET /cache/stats
     */
    public void handleStats() {
        System.out.println("  [GET /cache/stats]");
        CacheStats stats = cacheService.getStats();
        statsDisplay.printStats(stats);
    }

    /**
     * Expose the underlying CacheService for advanced demo operations.
     */
    public CacheService getCacheService() {
        return cacheService;
    }
}
