package com.systemdesign.autocomplete.repository;

import com.systemdesign.autocomplete.model.SearchQuery;

import java.util.List;

/**
 * QueryRepository — Persistence interface for search queries.
 *
 * WHY an interface?
 * -----------------
 * Repository pattern separates data access from business logic.
 * The service layer calls repository methods without knowing if data comes from:
 *   - In-memory ConcurrentHashMap (for this demo)
 *   - Redis / Memcached (for production caching layer)
 *   - MySQL / PostgreSQL (for durable storage)
 *   - Elasticsearch (for full-text search)
 *   - Apache Kafka consumer (for streaming ingestion)
 *
 * Wiring:
 *   AppConfig → creates InMemoryQueryRepository → injects into:
 *     - DataCollectionService (writes: save, incrementFrequency)
 *     - TrieBuilderService (reads: getAll, findTopK)
 */
public interface QueryRepository {

    /**
     * Save or update a search query.
     * If a query with the same text already exists, update its metadata.
     */
    void save(SearchQuery query);

    /**
     * Find all queries matching a prefix.
     * Used for building suggestions from raw query data.
     */
    List<SearchQuery> findByPrefix(String prefix);

    /**
     * Find the top-K most frequent queries overall.
     * Used for "popular searches" widget.
     */
    List<SearchQuery> findTopK(int limit);

    /**
     * Find trending queries: those whose recent frequency exceeds their average.
     *
     * @param timeWindowHours look at queries in the last N hours
     * @param limit           max results to return
     * @return queries that are "trending" (spiking in frequency)
     */
    List<SearchQuery> findTrending(int timeWindowHours, int limit);

    /**
     * Increment the frequency counter for a query.
     * Called every time a user performs this search.
     */
    void incrementFrequency(String queryText);

    /**
     * Get all stored queries.
     * Used by TrieBuilderService to rebuild the trie from scratch.
     */
    List<SearchQuery> getAll();

    /**
     * Get total number of stored queries.
     */
    int size();
}
