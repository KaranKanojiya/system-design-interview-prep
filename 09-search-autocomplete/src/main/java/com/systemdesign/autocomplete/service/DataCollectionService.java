package com.systemdesign.autocomplete.service;

import com.systemdesign.autocomplete.model.SearchQuery;
import com.systemdesign.autocomplete.repository.QueryRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * DataCollectionService — Logs and analyzes search queries.
 *
 * RESPONSIBILITIES:
 *   1. Record every search query (text + userId + timestamp)
 *   2. Track query frequency over time
 *   3. Detect TRENDING queries (frequency spikes)
 *   4. Provide analytics (top queries, trending, stats)
 *
 * TRENDING DETECTION:
 * -------------------
 * A query is "trending" if its RECENT frequency is significantly higher than its AVERAGE.
 *
 * Algorithm:
 *   1. Maintain per-query timestamps of recent searches (sliding window)
 *   2. Count searches in the last hour (recentCount)
 *   3. Calculate average hourly rate (totalFrequency / totalHoursTracked)
 *   4. If recentCount > 2 * averageRate → query is TRENDING
 *
 * This is a simplified version of the Z-score method used in production:
 *   z = (recentCount - mean) / stddev
 *   If z > 2.0 → trending (2 standard deviations above mean)
 *
 * Wiring:
 *   AppConfig → new DataCollectionService(queryRepository) → AutocompleteService
 *   AutocompleteService.recordQuery() → DataCollectionService.recordQuery()
 *   DataCollectionService → QueryRepository (persists query data)
 */
public class DataCollectionService {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** Repository for persisting search queries. */
    private final QueryRepository queryRepository;

    /**
     * Recent search timestamps per query, for trending detection.
     * Key: query text, Value: list of timestamps of recent searches.
     *
     * In production, this would be a sliding window counter (e.g., Redis sorted set)
     * or a streaming aggregation (Kafka Streams, Flink).
     */
    private final Map<String, List<LocalDateTime>> recentSearches;

    /** Total queries recorded (across all unique queries). */
    private long totalQueriesRecorded;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public DataCollectionService(QueryRepository queryRepository) {
        this.queryRepository = queryRepository;
        this.recentSearches = new ConcurrentHashMap<>();
        this.totalQueriesRecorded = 0;
    }

    // -----------------------------------------------------------------------
    // Record a query
    // -----------------------------------------------------------------------

    /**
     * Record that a user performed a search.
     *
     * Flow:
     *   1. Increment frequency in the repository
     *   2. Track timestamp for trending detection
     *   3. If userId is provided, save the full SearchQuery with user info
     *
     * @param queryText the text the user searched for
     * @param userId    the user who searched (null for anonymous)
     */
    public void recordQuery(String queryText, String userId) {
        if (queryText == null || queryText.isBlank()) {
            return;
        }

        String normalized = queryText.toLowerCase().trim();

        // Step 1: Increment frequency in repository
        queryRepository.incrementFrequency(normalized);

        // Step 2: Track timestamp for trending detection
        recentSearches.computeIfAbsent(normalized, k -> new ArrayList<>())
                .add(LocalDateTime.now());

        // Step 3: If user is known, save with user info
        if (userId != null && !userId.isBlank()) {
            SearchQuery query = SearchQuery.builder(normalized)
                    .userId(userId)
                    .lastSearched(LocalDateTime.now())
                    .build();
            queryRepository.save(query);
        }

        totalQueriesRecorded++;
    }

    // -----------------------------------------------------------------------
    // Analytics
    // -----------------------------------------------------------------------

    /**
     * Get the top-K most popular queries overall.
     * Sorted by frequency descending.
     */
    public List<SearchQuery> getTopQueries(int limit) {
        return queryRepository.findTopK(limit);
    }

    /**
     * Get trending queries — those with a recent frequency spike.
     *
     * TRENDING ALGORITHM:
     *   For each query searched in the last [timeWindowHours]:
     *     1. Count how many times it was searched in the last hour (recentCount)
     *     2. Get its total frequency from the repository (totalFreq)
     *     3. Calculate average rate: totalFreq / max(totalHoursTracked, 24)
     *        (use at least 24 hours to avoid false positives for brand-new queries)
     *     4. If recentCount > 2 * averageRate → it's trending!
     *
     * The "2x average" threshold is configurable in production:
     *   - Lower threshold (1.5x) → more sensitive, more false positives
     *   - Higher threshold (3x+) → fewer alerts, only major spikes
     *
     * @param limit           max trending queries to return
     * @param timeWindowHours how far back to look for trending (e.g., 1 hour, 24 hours)
     * @return list of trending SearchQuery objects, sorted by recent frequency desc
     */
    public List<SearchQuery> getTrendingQueries(int limit, int timeWindowHours) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(timeWindowHours);
        Map<String, Integer> recentCounts = new HashMap<>();

        // Count recent searches per query within the time window
        for (Map.Entry<String, List<LocalDateTime>> entry : recentSearches.entrySet()) {
            String queryText = entry.getKey();
            long recentCount = entry.getValue().stream()
                    .filter(ts -> ts.isAfter(cutoff))
                    .count();

            if (recentCount > 0) {
                recentCounts.put(queryText, (int) recentCount);
            }
        }

        // Filter for trending: recentCount > 2 * averageRate
        List<SearchQuery> trending = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : recentCounts.entrySet()) {
            String queryText = entry.getKey();
            int recentCount = entry.getValue();

            // Get total frequency from repository
            List<SearchQuery> found = queryRepository.findByPrefix(queryText);
            long totalFreq = found.stream()
                    .filter(q -> q.getQueryText().equals(queryText))
                    .mapToLong(SearchQuery::getFrequency)
                    .findFirst()
                    .orElse(0);

            // Calculate average hourly rate (assume at least 24 hours of tracking)
            double averageHourlyRate = totalFreq / Math.max(24.0, timeWindowHours);

            // Trending if recent count is more than 2x the average hourly rate
            if (recentCount > 2 * averageHourlyRate || recentCount >= 3) {
                trending.add(SearchQuery.builder(queryText)
                        .frequency(recentCount)
                        .lastSearched(LocalDateTime.now())
                        .build());
            }
        }

        // Sort by recent frequency descending
        trending.sort(Comparator.comparingLong(SearchQuery::getFrequency).reversed());
        return trending.subList(0, Math.min(limit, trending.size()));
    }

    // -----------------------------------------------------------------------
    // Stats
    // -----------------------------------------------------------------------

    /**
     * Total number of queries recorded (including duplicates).
     */
    public long getTotalQueriesRecorded() {
        return totalQueriesRecorded;
    }

    /**
     * Total unique queries in the repository.
     */
    public int getUniqueQueryCount() {
        return queryRepository.size();
    }
}
