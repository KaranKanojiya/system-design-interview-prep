package com.systemdesign.cache.strategy.eviction;

import com.systemdesign.cache.model.CacheEntry;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * TTLEvictionStrategy — Time-To-Live based eviction with lazy + active cleanup.
 *
 * TWO EXPIRATION APPROACHES:
 *
 *   1. LAZY expiration (on-demand):
 *      When get(key) is called, check if the key has expired. If yes, return null and remove it.
 *      Pros: No background thread. Zero overhead for keys that are never accessed again.
 *      Cons: Expired keys sit in memory until someone tries to access them. Memory leak risk.
 *      Used by: Redis (lazy deletion is one of Redis's expiration strategies).
 *
 *   2. ACTIVE cleanup (periodic sweep):
 *      cleanupExpired() scans the expiration index and removes all expired keys.
 *      Pros: Reclaims memory proactively. No stale data sitting around.
 *      Cons: CPU cost of scanning. Need to decide how often to run it.
 *      Used by: Redis (active expiration samples random keys periodically).
 *
 *   This implementation supports BOTH. CacheService uses lazy expiration on get().
 *   The demo app can call cleanupExpired() to show active cleanup.
 *
 * DATA STRUCTURE:
 *   TreeMap<LocalDateTime, Set<String>> expirationIndex
 *     Key: expiration time
 *     Value: set of keys that expire at that time
 *     WHY TreeMap? It's sorted by time → headMap(now) gives all expired entries in O(log n + k).
 *
 *   HashMap<String, LocalDateTime> keyToExpiration
 *     Reverse index: given a key, find its expiration time (for removal).
 *
 * WIRING: AppConfig creates TTLEvictionStrategy(capacity) → injects into CacheService.
 */
public class TTLEvictionStrategy implements EvictionStrategy {

    // Expiration index: sorted by expiry time → keys expiring at that time
    private final TreeMap<LocalDateTime, Set<String>> expirationIndex;

    // Reverse index: key → its expiration time (for O(1) removal)
    private final Map<String, LocalDateTime> keyToExpiration;

    // Track all keys (including those without TTL) for eviction fallback
    private final Map<String, CacheEntry> keyToEntry;

    private final int capacity;

    public TTLEvictionStrategy(int capacity) {
        this.capacity = capacity;
        this.expirationIndex = new TreeMap<>();
        this.keyToExpiration = new HashMap<>();
        this.keyToEntry = new HashMap<>();
    }

    /**
     * On get: no-op for TTL strategy.
     * Unlike LRU/LFU, accessing a key doesn't change its eviction priority.
     * The key expires when it expires, regardless of how many times you read it.
     */
    @Override
    public void onGet(String key) {
        // Intentionally empty — TTL-based eviction doesn't care about access patterns.
        // (Some caches do "touch TTL on access" — that would be a sliding-window TTL variant.)
    }

    /**
     * On put: register the key's expiration time in the index.
     */
    @Override
    public void onPut(String key, CacheEntry entry) {
        // Remove old entry if this key is being overwritten
        remove(key);

        keyToEntry.put(key, entry);

        if (entry.getExpiresAt() != null) {
            LocalDateTime expiresAt = entry.getExpiresAt();
            keyToExpiration.put(key, expiresAt);
            expirationIndex.computeIfAbsent(expiresAt, k -> new HashSet<>()).add(key);
        }
    }

    /**
     * Evict: remove the key that expires soonest.
     * If no keys have TTL, fall back to removing the oldest inserted key.
     *
     * Steps:
     *   1. If expirationIndex is not empty → remove first key from earliest expiry bucket
     *   2. Otherwise → remove the first key from keyToEntry (arbitrary, but deterministic)
     */
    @Override
    public String evict() {
        // First, try to evict already-expired keys
        String expired = evictExpired();
        if (expired != null) {
            return expired;
        }

        // No expired keys — evict the one closest to expiring
        if (!expirationIndex.isEmpty()) {
            Map.Entry<LocalDateTime, Set<String>> earliest = expirationIndex.firstEntry();
            Set<String> keys = earliest.getValue();
            String keyToEvict = keys.iterator().next();
            remove(keyToEvict);
            return keyToEvict;
        }

        // No TTL keys at all — evict any key (fallback)
        if (!keyToEntry.isEmpty()) {
            String keyToEvict = keyToEntry.keySet().iterator().next();
            remove(keyToEvict);
            return keyToEvict;
        }

        return null;
    }

    @Override
    public void remove(String key) {
        keyToEntry.remove(key);

        LocalDateTime expiresAt = keyToExpiration.remove(key);
        if (expiresAt != null) {
            Set<String> keysAtTime = expirationIndex.get(expiresAt);
            if (keysAtTime != null) {
                keysAtTime.remove(key);
                if (keysAtTime.isEmpty()) {
                    expirationIndex.remove(expiresAt);
                }
            }
        }
    }

    @Override
    public int size() {
        return keyToEntry.size();
    }

    @Override
    public String getEvictionPolicyName() {
        return "TTL (Time To Live)";
    }

    /**
     * Check if a specific key has expired (used for lazy expiration).
     * CacheService.get() calls this before returning a value.
     */
    public boolean isExpired(String key) {
        CacheEntry entry = keyToEntry.get(key);
        return entry != null && entry.isExpired();
    }

    /**
     * Active cleanup: scan and remove ALL expired keys.
     *
     * Uses TreeMap.headMap(now) to efficiently find all keys that expired before "now".
     * headMap returns a view — we need to copy the keys before modifying the map to avoid
     * ConcurrentModificationException.
     *
     * Returns the number of keys cleaned up.
     */
    public int cleanupExpired() {
        LocalDateTime now = LocalDateTime.now();
        int cleanedUp = 0;

        // headMap(now, true) → all entries with expiration <= now (inclusive)
        // We must collect first, then remove (can't modify TreeMap during iteration of headMap)
        Map<LocalDateTime, Set<String>> expiredEntries = new TreeMap<>(expirationIndex.headMap(now, true));

        for (Map.Entry<LocalDateTime, Set<String>> entry : expiredEntries.entrySet()) {
            for (String key : new HashSet<>(entry.getValue())) {
                remove(key);
                cleanedUp++;
            }
        }

        return cleanedUp;
    }

    // --- Private helpers ---

    /**
     * Try to evict an already-expired key (free cleanup).
     */
    private String evictExpired() {
        LocalDateTime now = LocalDateTime.now();
        Map.Entry<LocalDateTime, Set<String>> earliest = expirationIndex.firstEntry();

        if (earliest != null && !earliest.getKey().isAfter(now)) {
            // This key is already expired — free to evict
            Set<String> keys = earliest.getValue();
            String key = keys.iterator().next();
            remove(key);
            return key;
        }
        return null;
    }
}
