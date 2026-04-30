package com.systemdesign.cache.strategy.eviction;

import com.systemdesign.cache.model.CacheEntry;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * LFUEvictionStrategy — O(1) Least Frequently Used eviction using frequency buckets.
 *
 * THIS IS THE HARD INTERVIEW VARIANT: "Design an LFU Cache with O(1) get, put, and evict."
 *
 * KEY INSIGHT — Three maps working together:
 *
 *   1. keyToFreq:   key → current frequency   (how many times this key has been accessed)
 *   2. freqToKeys:  frequency → LinkedHashSet<key>  (all keys with this frequency, in insertion order)
 *   3. minFreq:     the current minimum frequency across all keys
 *
 * WHY LinkedHashSet (not just HashSet)?
 *   When two keys have the same frequency, we need a tiebreaker.
 *   LinkedHashSet maintains insertion order → the first key inserted at a given frequency
 *   is the one that's been at that frequency the longest → it gets evicted first.
 *   This makes it LFU with LRU as a tiebreaker — the standard interview expectation.
 *
 * HOW O(1) evict works:
 *   1. Look at minFreq → O(1)
 *   2. Get the LinkedHashSet for that frequency → O(1) map lookup
 *   3. Remove the first element (iterator().next()) → O(1) for LinkedHashSet
 *
 * HOW O(1) access works (incrementing frequency):
 *   1. Get current freq from keyToFreq → O(1)
 *   2. Remove key from freqToKeys[freq] → O(1) HashSet remove
 *   3. Add key to freqToKeys[freq+1] → O(1) HashSet add
 *   4. Update keyToFreq[key] = freq+1 → O(1)
 *   5. If freqToKeys[freq] is now empty AND freq == minFreq → minFreq++ → O(1)
 *
 * EXAMPLE walkthrough:
 *   put("a"), put("b"), put("c")  → all have freq=1, minFreq=1
 *   get("a"), get("a")            → "a" has freq=3, "b" and "c" have freq=1
 *   evict()                       → minFreq=1, keys at freq 1: {"b", "c"} → evicts "b" (first inserted)
 *
 * WIRING: AppConfig creates LFUEvictionStrategy(capacity) → injects into CacheService.
 */
public class LFUEvictionStrategy implements EvictionStrategy {

    private final Map<String, Integer> keyToFreq;                   // key → frequency
    private final Map<Integer, LinkedHashSet<String>> freqToKeys;   // frequency → set of keys
    private int minFreq;                                             // current minimum frequency
    private final int capacity;

    public LFUEvictionStrategy(int capacity) {
        this.capacity = capacity;
        this.keyToFreq = new HashMap<>();
        this.freqToKeys = new HashMap<>();
        this.minFreq = 0;
    }

    /**
     * On get: increment the key's frequency.
     *
     * Steps (all O(1)):
     *   1. Get current freq → remove key from freq bucket
     *   2. Add key to freq+1 bucket
     *   3. If the old freq bucket is now empty and was the minFreq → bump minFreq
     */
    @Override
    public void onGet(String key) {
        if (!keyToFreq.containsKey(key)) {
            return;
        }
        incrementFrequency(key);
    }

    /**
     * On put: register the key with frequency 1.
     * If the key already exists, just increment its frequency.
     */
    @Override
    public void onPut(String key, CacheEntry entry) {
        if (keyToFreq.containsKey(key)) {
            // Key already tracked — treat as an access
            incrementFrequency(key);
            return;
        }

        // New key starts at frequency 1
        keyToFreq.put(key, 1);
        freqToKeys.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);

        // New key always has the minimum possible frequency
        minFreq = 1;
    }

    /**
     * Evict the least frequently used key.
     *
     * Steps:
     *   1. Get the set of keys at minFreq → O(1) map lookup
     *   2. Remove the first key in that set (insertion order = LRU tiebreaker) → O(1)
     *   3. Clean up the frequency maps
     *
     * If two keys both have freq=1, we evict the one that was inserted first.
     * This is because LinkedHashSet.iterator() returns elements in insertion order.
     */
    @Override
    public String evict() {
        if (keyToFreq.isEmpty()) {
            return null;
        }

        // Get all keys with the minimum frequency
        LinkedHashSet<String> keysAtMinFreq = freqToKeys.get(minFreq);
        if (keysAtMinFreq == null || keysAtMinFreq.isEmpty()) {
            return null;
        }

        // Remove the first key (oldest at this frequency) — the LRU tiebreaker
        String evictedKey = keysAtMinFreq.iterator().next();
        keysAtMinFreq.remove(evictedKey);

        // Clean up if the bucket is now empty
        if (keysAtMinFreq.isEmpty()) {
            freqToKeys.remove(minFreq);
            // Note: we don't need to update minFreq here because this is called
            // before a new put, which will set minFreq = 1 anyway
        }

        keyToFreq.remove(evictedKey);
        return evictedKey;
    }

    @Override
    public void remove(String key) {
        Integer freq = keyToFreq.remove(key);
        if (freq != null) {
            LinkedHashSet<String> keys = freqToKeys.get(freq);
            if (keys != null) {
                keys.remove(key);
                if (keys.isEmpty()) {
                    freqToKeys.remove(freq);
                }
            }
        }
    }

    @Override
    public int size() {
        return keyToFreq.size();
    }

    @Override
    public String getEvictionPolicyName() {
        return "LFU (Least Frequently Used)";
    }

    /**
     * Get the current frequency of a key (for display/debugging).
     */
    public int getFrequency(String key) {
        return keyToFreq.getOrDefault(key, 0);
    }

    /**
     * Get the current minimum frequency (for display/debugging).
     */
    public int getMinFrequency() {
        return minFreq;
    }

    // ===========================================================================================
    // Private helper — the core O(1) frequency increment operation.
    //
    // This is the trickiest part of LFU. Walk through it carefully:
    //   Before: key "x" is at freq=3, minFreq=2
    //   After:  key "x" is at freq=4, minFreq=2 (unchanged, because freq 2 bucket isn't empty)
    //
    //   Before: key "x" is at freq=2, minFreq=2, and "x" is the ONLY key at freq=2
    //   After:  key "x" is at freq=3, minFreq=3 (bumped, because freq 2 bucket is now empty)
    // ===========================================================================================
    private void incrementFrequency(String key) {
        int oldFreq = keyToFreq.get(key);
        int newFreq = oldFreq + 1;

        // Move key from old frequency bucket to new frequency bucket
        keyToFreq.put(key, newFreq);

        // Remove from old bucket
        LinkedHashSet<String> oldBucket = freqToKeys.get(oldFreq);
        oldBucket.remove(key);

        // If old bucket is empty AND it was the minFreq → increment minFreq
        if (oldBucket.isEmpty()) {
            freqToKeys.remove(oldFreq);
            if (minFreq == oldFreq) {
                minFreq = newFreq;
            }
        }

        // Add to new bucket
        freqToKeys.computeIfAbsent(newFreq, k -> new LinkedHashSet<>()).add(key);
    }
}
