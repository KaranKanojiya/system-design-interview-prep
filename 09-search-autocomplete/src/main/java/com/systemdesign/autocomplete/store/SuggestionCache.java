package com.systemdesign.autocomplete.store;

import com.systemdesign.autocomplete.model.Suggestion;

import java.util.List;
import java.util.Optional;

/**
 * SuggestionCache — Interface for caching autocomplete suggestions.
 *
 * WHY cache?
 * ----------
 * Even with TopKTrie's O(1) lookup, caching is essential because:
 *   1. Popular prefixes ("a", "th", "ho") are queried millions of times/day
 *   2. Ranking + filtering add overhead on top of the trie lookup
 *   3. Cache avoids repeated computation of the full pipeline:
 *      trie lookup → ranking → filtering → serialization
 *   4. In distributed systems, cache reduces load on the trie service
 *
 * Cache key: normalized prefix string
 * Cache value: List<Suggestion> (the final ranked, filtered result)
 *
 * INVALIDATION STRATEGY:
 *   - TTL-based: entries expire after cacheTtlSeconds (default 300s / 5min)
 *   - LRU eviction: when cache is full, evict least recently used entries
 *   - Manual invalidation: when a query is recorded, invalidate its prefix(es)
 *
 * Wiring:
 *   AppConfig → creates InMemorySuggestionCache → injects into AutocompleteService
 *   AutocompleteService.getSuggestions():
 *     1. cache.get(prefix) → if present, return immediately (cache HIT)
 *     2. if absent → query trie, rank, filter, then cache.put(prefix, results)
 */
public interface SuggestionCache {

    /**
     * Get cached suggestions for a prefix.
     *
     * @param prefix the normalized prefix
     * @return Optional containing the suggestions if cached, empty if cache miss
     */
    Optional<List<Suggestion>> get(String prefix);

    /**
     * Store suggestions in the cache.
     *
     * @param prefix      the normalized prefix (cache key)
     * @param suggestions the suggestions to cache (cache value)
     */
    void put(String prefix, List<Suggestion> suggestions);

    /**
     * Invalidate (remove) a specific prefix from the cache.
     * Called when new data for this prefix is inserted.
     */
    void invalidate(String prefix);

    /**
     * Clear the entire cache.
     * Called on trie rebuild or data refresh.
     */
    void invalidateAll();

    /**
     * Get the number of entries currently in the cache.
     */
    int size();

    /**
     * Get the cache hit rate (hits / total requests).
     * Useful for monitoring cache effectiveness.
     *
     * @return hit rate as a value between 0.0 and 1.0
     */
    double getHitRate();
}
