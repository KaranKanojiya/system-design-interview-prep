package com.systemdesign.cache.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * CacheStats — Thread-safe hit/miss tracking using AtomicLong.
 *
 * WHY AtomicLong instead of plain long?
 * --------------------------------------
 * In a distributed cache, multiple threads serve requests concurrently.
 * Plain long++ is NOT atomic (it's read-modify-write = 3 CPU operations).
 * Two threads doing hits++ simultaneously can lose an increment.
 * AtomicLong.incrementAndGet() uses CAS (Compare-And-Swap) — lock-free, thread-safe.
 *
 * WIRING: CacheService holds a CacheStats instance.
 *   - CacheService.get() → on hit: stats.recordHit() / on miss: stats.recordMiss()
 *   - CacheService.put() → stats.recordPut()
 *   - CacheService.evict() → stats.recordEviction()
 *   - CacheController.handleStats() → stats.getHitRate() for display
 */
public class CacheStats {

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong puts = new AtomicLong(0);

    // --- Recording methods (called by CacheService) ---

    public void recordHit() {
        hits.incrementAndGet();
        totalRequests.incrementAndGet();
    }

    public void recordMiss() {
        misses.incrementAndGet();
        totalRequests.incrementAndGet();
    }

    public void recordEviction() {
        evictions.incrementAndGet();
    }

    public void recordPut() {
        puts.incrementAndGet();
    }

    // --- Computed metrics ---

    /**
     * Hit rate = hits / (hits + misses).
     * A good cache should have 80%+ hit rate. Below 50% means the cache is not effective.
     * Returns 0.0 if no requests have been made (avoid division by zero).
     */
    public double getHitRate() {
        long totalLookups = hits.get() + misses.get();
        if (totalLookups == 0) return 0.0;
        return (double) hits.get() / totalLookups;
    }

    /**
     * Miss rate = 1 - hitRate.
     * Every miss potentially means a slow database query or API call.
     */
    public double getMissRate() {
        return 1.0 - getHitRate();
    }

    // --- Getters ---

    public long getHits() { return hits.get(); }
    public long getMisses() { return misses.get(); }
    public long getEvictions() { return evictions.get(); }
    public long getTotalRequests() { return totalRequests.get(); }
    public long getPuts() { return puts.get(); }

    /** Reset all stats — useful when switching eviction strategies for comparison. */
    public void reset() {
        hits.set(0);
        misses.set(0);
        evictions.set(0);
        totalRequests.set(0);
        puts.set(0);
    }

    @Override
    public String toString() {
        return String.format(
                "CacheStats{hits=%d, misses=%d, evictions=%d, puts=%d, total=%d, hitRate=%.2f%%}",
                hits.get(), misses.get(), evictions.get(), puts.get(),
                totalRequests.get(), getHitRate() * 100);
    }
}
