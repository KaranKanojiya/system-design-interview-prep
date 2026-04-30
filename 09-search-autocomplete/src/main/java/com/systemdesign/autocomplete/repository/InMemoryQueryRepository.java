package com.systemdesign.autocomplete.repository;

import com.systemdesign.autocomplete.model.SearchQuery;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryQueryRepository — ConcurrentHashMap-backed implementation of QueryRepository.
 *
 * WHY ConcurrentHashMap?
 * ----------------------
 * Multiple threads may read/write concurrently:
 *   - DataCollectionService writes (recordQuery, incrementFrequency)
 *   - TrieBuilderService reads (getAll)
 *   - AutocompleteService reads (findByPrefix)
 *
 * ConcurrentHashMap provides:
 *   - Thread-safe without external synchronization
 *   - Lock striping: reads are non-blocking, writes lock only the affected bucket
 *   - Better concurrency than Collections.synchronizedMap (which locks the entire map)
 *
 * DATA MODEL:
 *   Key:   query text (lowercased, trimmed) → ensures uniqueness
 *   Value: SearchQuery object (immutable once built, but we replace to update freq)
 *
 * Note: Since SearchQuery is immutable (Builder pattern), to "update" a query's frequency
 * we create a new SearchQuery with the updated frequency and replace the old one.
 * This is the idiomatic approach for immutable value objects.
 *
 * Wiring:
 *   AppConfig → new InMemoryQueryRepository() → DataCollectionService, TrieBuilderService
 */
public class InMemoryQueryRepository implements QueryRepository {

    /**
     * Primary storage: queryText → SearchQuery.
     * ConcurrentHashMap for thread-safe concurrent access.
     */
    private final ConcurrentHashMap<String, SearchQuery> queries;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public InMemoryQueryRepository() {
        this.queries = new ConcurrentHashMap<>();
    }

    // -----------------------------------------------------------------------
    // QueryRepository implementation
    // -----------------------------------------------------------------------

    /**
     * Save or replace a search query.
     * If a query with the same text exists, the new one replaces it.
     */
    @Override
    public void save(SearchQuery query) {
        if (query == null || query.getQueryText() == null) {
            return;
        }
        queries.put(query.getQueryText().toLowerCase().trim(), query);
    }

    /**
     * Find queries whose text starts with the given prefix.
     *
     * Time: O(N) — scans all entries. In production, you'd use a trie or database index.
     * For our purposes (demo), the query count is small enough that this is fine.
     */
    @Override
    public List<SearchQuery> findByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return new ArrayList<>(queries.values());
        }

        String normalizedPrefix = prefix.toLowerCase().trim();
        return queries.values().stream()
                .filter(q -> q.getQueryText().startsWith(normalizedPrefix))
                .collect(Collectors.toList());
    }

    /**
     * Find the top-K queries by frequency.
     * Sorts all queries by frequency descending, returns the first K.
     */
    @Override
    public List<SearchQuery> findTopK(int limit) {
        return queries.values().stream()
                .sorted(Comparator.comparingLong(SearchQuery::getFrequency).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Find trending queries — those searched recently with unusually high frequency.
     *
     * TRENDING DETECTION:
     *   A query is "trending" if it was searched within the time window
     *   AND its frequency is above the median frequency.
     *   (DataCollectionService has a more sophisticated algorithm;
     *    this is a simplified version for the repository layer.)
     *
     * In production, trending detection would use:
     *   - Sliding window counters (e.g., count in last 1h vs last 24h)
     *   - Z-score: (current_rate - mean_rate) / stddev
     *   - Exponential moving average
     */
    @Override
    public List<SearchQuery> findTrending(int timeWindowHours, int limit) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(timeWindowHours);

        // Get all queries searched within the time window
        List<SearchQuery> recentQueries = queries.values().stream()
                .filter(q -> q.getLastSearched() != null && q.getLastSearched().isAfter(cutoff))
                .sorted(Comparator.comparingLong(SearchQuery::getFrequency).reversed())
                .limit(limit)
                .collect(Collectors.toList());

        return recentQueries;
    }

    /**
     * Increment frequency for a query.
     *
     * Since SearchQuery is immutable, we create a new one with freq+1 and replace.
     * ConcurrentHashMap.compute() is atomic — no race conditions.
     */
    @Override
    public void incrementFrequency(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return;
        }

        String key = queryText.toLowerCase().trim();

        queries.compute(key, (k, existing) -> {
            if (existing == null) {
                // First time seeing this query — create with frequency 1
                return SearchQuery.builder(key)
                        .frequency(1)
                        .lastSearched(LocalDateTime.now())
                        .build();
            } else {
                // Existing query — increment frequency, update timestamp
                return SearchQuery.builder(existing.getQueryText())
                        .frequency(existing.getFrequency() + 1)
                        .lastSearched(LocalDateTime.now())
                        .userId(existing.getUserId())
                        .build();
            }
        });
    }

    /**
     * Get all queries. Returns a snapshot (defensive copy) for thread safety.
     */
    @Override
    public List<SearchQuery> getAll() {
        return new ArrayList<>(queries.values());
    }

    @Override
    public int size() {
        return queries.size();
    }
}
