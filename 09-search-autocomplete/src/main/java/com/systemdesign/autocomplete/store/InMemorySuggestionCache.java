package com.systemdesign.autocomplete.store;

import com.systemdesign.autocomplete.model.Suggestion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * InMemorySuggestionCache — LRU cache for autocomplete suggestions.
 *
 * LRU (Least Recently Used) IMPLEMENTATION:
 * ------------------------------------------
 * Uses LinkedHashMap with accessOrder=true. This is the classic Java trick for LRU:
 *
 *   LinkedHashMap(capacity, loadFactor, accessOrder=true)
 *
 * When accessOrder=true:
 *   - get() moves the accessed entry to the END of the iteration order
 *   - The BEGINNING of the iteration order is the LEAST recently used entry
 *   - Override removeEldestEntry() to auto-evict when size > capacity
 *
 * WHY LinkedHashMap over a custom doubly-linked list + HashMap?
 *   Both achieve O(1) get/put/evict. LinkedHashMap is:
 *   - Built into Java (no custom implementation needed)
 *   - Well-tested and optimized
 *   - Fewer bugs (custom DLL implementations are error-prone)
 *
 * Ugly approach (manual LRU):
 *   class LRUCache {
 *       Map<String, Node> map = new HashMap<>();
 *       Node head, tail; // doubly linked list
 *       void moveToFront(Node n) {
 *           // Remove from current position
 *           n.prev.next = n.next;
 *           n.next.prev = n.prev;
 *           // Add to front
 *           n.next = head.next;
 *           n.prev = head;
 *           head.next.prev = n;
 *           head.next = n;
 *       }
 *       // 30+ lines of pointer manipulation, easy to get wrong
 *   }
 *
 * Clean approach (this class):
 *   LinkedHashMap handles all of this internally. We just override removeEldestEntry.
 *
 * THREAD SAFETY:
 *   All public methods are synchronized. This is simple but limits concurrency.
 *   For production, you'd use:
 *     - ConcurrentHashMap + ConcurrentLinkedDeque (manual but concurrent)
 *     - Caffeine library (high-performance concurrent cache)
 *     - Guava Cache (similar to Caffeine)
 *   But for interview purposes, synchronized is perfectly fine.
 *
 * Wiring:
 *   AppConfig → new InMemorySuggestionCache(config.getCacheSize()) → AutocompleteService
 */
public class InMemorySuggestionCache implements SuggestionCache {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** The LRU cache backed by LinkedHashMap. */
    private final LinkedHashMap<String, CacheEntry> cache;

    /** Maximum number of entries. */
    private final int maxSize;

    /** TTL in seconds. Entries older than this are considered stale. */
    private final int ttlSeconds;

    // Stats tracking
    private long hits;
    private long misses;

    // -----------------------------------------------------------------------
    // Cache entry wrapper (stores value + insertion timestamp)
    // -----------------------------------------------------------------------

    /**
     * Wraps the cached value with a timestamp for TTL-based expiration.
     */
    private static class CacheEntry {
        final List<Suggestion> suggestions;
        final long createdAt; // epoch millis

        CacheEntry(List<Suggestion> suggestions) {
            this.suggestions = suggestions;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired(int ttlSeconds) {
            long ageMillis = System.currentTimeMillis() - createdAt;
            return ageMillis > (long) ttlSeconds * 1000;
        }
    }

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Create an LRU cache with the given capacity and TTL.
     *
     * @param maxSize    maximum number of cached prefixes
     * @param ttlSeconds time-to-live for each entry in seconds
     */
    public InMemorySuggestionCache(int maxSize, int ttlSeconds) {
        this.maxSize = maxSize;
        this.ttlSeconds = ttlSeconds;
        this.hits = 0;
        this.misses = 0;

        // THE LRU MAGIC:
        // LinkedHashMap(initialCapacity, loadFactor, accessOrder)
        //   accessOrder=true → iteration order = access order (most recent last)
        //   Override removeEldestEntry → auto-evict when size > maxSize
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                // When the map grows beyond maxSize, remove the least recently used entry
                // This is called automatically after every put()
                return size() > maxSize;
            }
        };
    }

    // -----------------------------------------------------------------------
    // SuggestionCache implementation
    // -----------------------------------------------------------------------

    /**
     * Get cached suggestions. Returns empty Optional on miss or expired entry.
     *
     * Flow:
     *   1. Look up prefix in the LinkedHashMap (O(1))
     *   2. If found, check TTL — if expired, remove and count as miss
     *   3. If fresh, return the suggestions (cache HIT)
     *   4. If not found, return empty (cache MISS)
     *
     * The get() call on LinkedHashMap also moves the entry to the end (most recently used).
     */
    @Override
    public synchronized Optional<List<Suggestion>> get(String prefix) {
        if (prefix == null) {
            misses++;
            return Optional.empty();
        }

        String key = prefix.toLowerCase().trim();
        CacheEntry entry = cache.get(key);

        if (entry == null) {
            misses++;
            return Optional.empty();
        }

        // Check TTL
        if (entry.isExpired(ttlSeconds)) {
            cache.remove(key);
            misses++;
            return Optional.empty();
        }

        // Cache HIT — return a defensive copy so callers can't modify the cached list
        hits++;
        return Optional.of(new ArrayList<>(entry.suggestions));
    }

    /**
     * Store suggestions in the cache.
     * If the cache is full, the LRU entry is automatically evicted by removeEldestEntry.
     */
    @Override
    public synchronized void put(String prefix, List<Suggestion> suggestions) {
        if (prefix == null || suggestions == null) {
            return;
        }

        String key = prefix.toLowerCase().trim();
        // Store a defensive copy so external modifications don't corrupt the cache
        cache.put(key, new CacheEntry(new ArrayList<>(suggestions)));
    }

    /**
     * Remove a specific prefix from the cache.
     * Called when the trie is updated with new data for this prefix.
     */
    @Override
    public synchronized void invalidate(String prefix) {
        if (prefix != null) {
            cache.remove(prefix.toLowerCase().trim());
        }
    }

    /**
     * Clear the entire cache.
     * Called on trie rebuild to ensure fresh data.
     */
    @Override
    public synchronized void invalidateAll() {
        cache.clear();
        // Don't reset hits/misses — they track lifetime stats
    }

    @Override
    public synchronized int size() {
        return cache.size();
    }

    /**
     * Calculate the cache hit rate.
     *
     * hit rate = hits / (hits + misses)
     *
     * A good autocomplete cache should achieve 70-90% hit rate because:
     *   - Popular prefixes (e.g., "the", "how") are queried repeatedly
     *   - Zipf's law: a small number of prefixes account for most queries
     */
    @Override
    public synchronized double getHitRate() {
        long total = hits + misses;
        if (total == 0) {
            return 0.0;
        }
        return (double) hits / total;
    }

    /**
     * Get raw hit count (for display).
     */
    public synchronized long getHits() {
        return hits;
    }

    /**
     * Get raw miss count (for display).
     */
    public synchronized long getMisses() {
        return misses;
    }

    /**
     * Reset stats counters.
     */
    public synchronized void resetStats() {
        hits = 0;
        misses = 0;
    }
}
