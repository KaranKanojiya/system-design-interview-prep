package com.systemdesign.videostreaming.service;

import com.systemdesign.videostreaming.model.Resolution;
import com.systemdesign.videostreaming.model.VideoChunk;
import com.systemdesign.videostreaming.store.VideoStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulated Content Delivery Network (CDN) with LRU cache.
 *
 * CDN architecture:
 *   1. User requests a video chunk → routed to nearest edge PoP (Point of Presence)
 *   2. Edge checks local cache:
 *      - HIT:  return immediately (fast, ~10ms latency)
 *      - MISS: fetch from origin (slow, ~200ms), cache at edge, then return
 *   3. Popular videos stay in cache (high hit rate = low origin load)
 *   4. Unpopular videos get evicted (LRU) → next request is a cache miss
 *
 * Why LRU eviction?
 *   - Edge servers have limited storage (e.g., 100TB per PoP)
 *   - Must evict SOMETHING when cache is full
 *   - LRU assumes recent popularity predicts future popularity
 *   - Alternative: LFU (Least Frequently Used) — better for some workloads
 *
 * In production: CloudFront/Akamai/Cloudflare with 200+ edge locations.
 * Cache key: "{videoId}/{resolution}/{chunkIndex}"
 */
public class CDNService {

    private final VideoStore originStore;  // "Origin" = S3 (the source of truth)
    private final Map<String, VideoChunk> cache;  // LRU cache at the edge
    private final int maxCacheSize;

    // Hit/miss tracking for stats
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    /**
     * Simulated latencies (in milliseconds):
     * Cache hit: fast (edge server responds directly)
     * Cache miss: slow (must fetch from origin S3 across the network)
     */
    private static final long CACHE_HIT_LATENCY_MS = 5;
    private static final long CACHE_MISS_LATENCY_MS = 100;

    public CDNService(VideoStore originStore, int maxCacheSize) {
        this.originStore = originStore;
        this.maxCacheSize = maxCacheSize;

        // LinkedHashMap with accessOrder=true implements LRU:
        // - Accessing an entry moves it to the tail (most recently used)
        // - When size exceeds max, the eldest entry (head = least recently used) is removed
        this.cache = new LinkedHashMap<>(maxCacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, VideoChunk> eldest) {
                return size() > maxCacheSize;
            }
        };
    }

    /**
     * Fetch a video chunk, using the CDN cache with LRU eviction.
     *
     * Returns null if the chunk doesn't exist even at origin.
     * Simulates latency to demonstrate cache performance difference.
     */
    public VideoChunk getChunk(String videoId, Resolution resolution, int chunkIndex) {
        String cacheKey = buildCacheKey(videoId, resolution, chunkIndex);

        // Check cache first (the CDN edge)
        synchronized (cache) {
            VideoChunk cached = cache.get(cacheKey);
            if (cached != null) {
                cacheHits.incrementAndGet();
                simulateLatency(CACHE_HIT_LATENCY_MS);
                return cached;
            }
        }

        // Cache miss — fetch from origin (S3)
        cacheMisses.incrementAndGet();
        simulateLatency(CACHE_MISS_LATENCY_MS);

        VideoChunk fromOrigin = originStore.getChunk(videoId, resolution, chunkIndex);
        if (fromOrigin != null) {
            // Cache the chunk at the edge for future requests
            synchronized (cache) {
                cache.put(cacheKey, fromOrigin);
            }
        }

        return fromOrigin;
    }

    /**
     * Simulate network latency.
     * In production: this is real network round-trip time.
     */
    private void simulateLatency(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Warm the cache by pre-loading a chunk (used for popular content pre-caching).
     */
    public void warmCache(String videoId, Resolution resolution, int chunkIndex) {
        VideoChunk chunk = originStore.getChunk(videoId, resolution, chunkIndex);
        if (chunk != null) {
            String cacheKey = buildCacheKey(videoId, resolution, chunkIndex);
            synchronized (cache) {
                cache.put(cacheKey, chunk);
            }
        }
    }

    private String buildCacheKey(String videoId, Resolution resolution, int chunkIndex) {
        return videoId + "/" + (resolution != null ? resolution.name() : "SOURCE") + "/" + chunkIndex;
    }

    // ─── Stats ──────────────────────────────────────────────────────────

    public long getCacheHits() { return cacheHits.get(); }
    public long getCacheMisses() { return cacheMisses.get(); }
    public long getTotalRequests() { return cacheHits.get() + cacheMisses.get(); }

    /**
     * Cache hit rate as a percentage (0-100).
     * In production: this is a key SLA metric. Target: >95% for popular content.
     */
    public double getHitRate() {
        long total = getTotalRequests();
        if (total == 0) return 0.0;
        return (cacheHits.get() * 100.0) / total;
    }

    public int getCacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    public int getMaxCacheSize() { return maxCacheSize; }

    /** Reset hit/miss counters (for per-demo fresh stats). */
    public void resetStats() {
        cacheHits.set(0);
        cacheMisses.set(0);
    }
}
