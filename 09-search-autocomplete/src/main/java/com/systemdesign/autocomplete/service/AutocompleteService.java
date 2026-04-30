package com.systemdesign.autocomplete.service;

import com.systemdesign.autocomplete.model.AutocompleteConfig;
import com.systemdesign.autocomplete.model.SearchContext;
import com.systemdesign.autocomplete.model.Suggestion;
import com.systemdesign.autocomplete.store.SuggestionCache;
import com.systemdesign.autocomplete.strategy.filtering.FilterStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * AutocompleteService — The FACADE that orchestrates the entire autocomplete pipeline.
 *
 * FACADE PATTERN:
 * ---------------
 * This class is the single entry point for all autocomplete operations.
 * It hides the complexity of the internal subsystems:
 *   - TrieService (trie lookup, thread safety)
 *   - RankingService (scoring, re-ordering)
 *   - FilterStrategy (profanity, spam removal)
 *   - SuggestionCache (caching, TTL)
 *   - DataCollectionService (logging, trending)
 *
 * Without the facade, the controller would need to know about ALL these services
 * and call them in the right order. The facade encapsulates the pipeline.
 *
 * THE AUTOCOMPLETE PIPELINE:
 * ==========================
 *
 *   User types "app"
 *        │
 *        ▼
 *   ┌─────────────────────┐
 *   │  1. CHECK CACHE      │ → cache.get("app")
 *   │     Hit? Return!     │    Optional<List<Suggestion>>
 *   └────────┬────────────┘
 *            │ cache miss
 *            ▼
 *   ┌─────────────────────┐
 *   │  2. QUERY TRIE       │ → trieService.getSuggestions("app", 10)
 *   │     TopKTrie = O(1)  │    List<Suggestion>
 *   └────────┬────────────┘
 *            │ raw suggestions
 *            ▼
 *   ┌─────────────────────┐
 *   │  3. FILTER           │ → profanityFilter.filter(suggestions)
 *   │     Remove bad words │    List<Suggestion> (smaller or same)
 *   └────────┬────────────┘
 *            │ filtered suggestions
 *            ▼
 *   ┌─────────────────────┐
 *   │  4. RANK             │ → rankingService.rankSuggestions(suggestions, context)
 *   │     Score + sort     │    List<Suggestion> (re-ordered)
 *   └────────┬────────────┘
 *            │ ranked suggestions
 *            ▼
 *   ┌─────────────────────┐
 *   │  5. CACHE RESULT     │ → cache.put("app", rankedSuggestions)
 *   └────────┬────────────┘
 *            │
 *            ▼
 *   Return rankedSuggestions to user
 *
 * Wiring:
 *   AppConfig → creates all dependencies → new AutocompleteService(all deps) → Controller
 */
public class AutocompleteService {

    // -----------------------------------------------------------------------
    // Fields — all injected via constructor (Dependency Injection)
    // -----------------------------------------------------------------------

    private final TrieService trieService;
    private final RankingService rankingService;
    private final List<FilterStrategy> filters;
    private final SuggestionCache cache;
    private final DataCollectionService dataCollectionService;
    private final AutocompleteConfig config;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * All dependencies are injected — this class creates NOTHING internally.
     * This makes it testable (mock any dependency) and flexible (swap implementations).
     */
    public AutocompleteService(
            TrieService trieService,
            RankingService rankingService,
            List<FilterStrategy> filters,
            SuggestionCache cache,
            DataCollectionService dataCollectionService,
            AutocompleteConfig config) {
        this.trieService = trieService;
        this.rankingService = rankingService;
        this.filters = filters;
        this.cache = cache;
        this.dataCollectionService = dataCollectionService;
        this.config = config;
    }

    // -----------------------------------------------------------------------
    // getSuggestions — THE MAIN METHOD
    // -----------------------------------------------------------------------

    /**
     * Get autocomplete suggestions for a prefix.
     *
     * This is the complete pipeline: cache → trie → filter → rank → cache → return.
     *
     * @param prefix  what the user has typed so far
     * @param context search context (user info, timestamp, locale)
     * @return ranked, filtered suggestions (empty list if no results)
     */
    public List<Suggestion> getSuggestions(String prefix, SearchContext context) {
        // Validate and normalize prefix
        if (prefix == null) {
            return Collections.emptyList();
        }

        String normalizedPrefix = prefix.toLowerCase().trim();

        // Enforce max prefix length (prevent abuse from extremely long inputs)
        if (normalizedPrefix.length() > config.getMaxPrefixLength()) {
            normalizedPrefix = normalizedPrefix.substring(0, config.getMaxPrefixLength());
        }

        // ---- Step 1: Check cache ----
        // Cache key includes only prefix (not context) for simplicity.
        // In production, you might include userId in the key for personalized caching.
        Optional<List<Suggestion>> cached = cache.get(normalizedPrefix);
        if (cached.isPresent()) {
            // CACHE HIT — return immediately, skip the pipeline
            return cached.get();
        }

        // ---- Step 2: Query trie (CACHE MISS) ----
        // This is O(1) with TopKTrie, O(L+N) with StandardTrie
        List<Suggestion> suggestions = trieService.getSuggestions(
                normalizedPrefix, config.getMaxResults());

        if (suggestions.isEmpty()) {
            // No results — cache the empty result too (negative caching)
            // WHY? Prevents repeated trie lookups for non-existent prefixes
            cache.put(normalizedPrefix, suggestions);
            return suggestions;
        }

        // Make a mutable copy (trie might return unmodifiable list)
        suggestions = new ArrayList<>(suggestions);

        // ---- Step 3: Apply filters (profanity, spam, etc.) ----
        // Chain of Responsibility: each filter passes its output to the next
        for (FilterStrategy filter : filters) {
            suggestions = filter.filter(suggestions);
        }

        // ---- Step 4: Apply ranking ----
        // Re-scores and re-sorts based on the context (time-decay, personalization)
        suggestions = rankingService.rankSuggestions(suggestions, context);

        // Trim to maxResults (filters may have removed some, ranking may want to show fewer)
        if (suggestions.size() > config.getMaxResults()) {
            suggestions = new ArrayList<>(suggestions.subList(0, config.getMaxResults()));
        }

        // ---- Step 5: Cache the result ----
        cache.put(normalizedPrefix, suggestions);

        // ---- Step 6: Return ----
        return suggestions;
    }

    // -----------------------------------------------------------------------
    // recordQuery — Feed data back into the system
    // -----------------------------------------------------------------------

    /**
     * Record that a user executed a search query.
     * This feeds the data collection pipeline for:
     *   - Frequency tracking
     *   - Trending detection
     *   - Personalization data
     *   - Future trie rebuilds
     *
     * Also invalidates cache entries affected by the new data.
     *
     * @param queryText the complete query text
     * @param userId    the user (null for anonymous)
     */
    public void recordQuery(String queryText, String userId) {
        if (queryText == null || queryText.isBlank()) {
            return;
        }

        String normalized = queryText.toLowerCase().trim();

        // Record in the data collection service
        dataCollectionService.recordQuery(normalized, userId);

        // Insert/update in the trie (so future suggestions include this query)
        trieService.insertQuery(normalized, 1);

        // Invalidate affected cache entries
        // WHY? The trie now has new data — cached results for this prefix are stale
        // We invalidate all prefixes of the query (e.g., for "apple": "a", "ap", "app", etc.)
        for (int i = 1; i <= normalized.length(); i++) {
            cache.invalidate(normalized.substring(0, i));
        }
    }

    // -----------------------------------------------------------------------
    // Accessors for components (used by controller/display)
    // -----------------------------------------------------------------------

    public TrieService getTrieService() {
        return trieService;
    }

    public RankingService getRankingService() {
        return rankingService;
    }

    public DataCollectionService getDataCollectionService() {
        return dataCollectionService;
    }

    public SuggestionCache getCache() {
        return cache;
    }

    public AutocompleteConfig getConfig() {
        return config;
    }
}
