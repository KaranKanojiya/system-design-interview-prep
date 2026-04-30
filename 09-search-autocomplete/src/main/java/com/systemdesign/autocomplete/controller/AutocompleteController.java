package com.systemdesign.autocomplete.controller;

import com.systemdesign.autocomplete.model.SearchContext;
import com.systemdesign.autocomplete.model.SearchQuery;
import com.systemdesign.autocomplete.model.Suggestion;
import com.systemdesign.autocomplete.service.AutocompleteService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AutocompleteController — Simulated REST controller for autocomplete endpoints.
 *
 * In a real system, this would be a Spring @RestController or a Servlet.
 * Here we simulate the HTTP layer with plain Java methods.
 *
 * ENDPOINTS (simulated):
 *   GET  /autocomplete?q={prefix}&userId={userId}   → handleSearch()
 *   POST /query?q={queryText}&userId={userId}        → handleRecordQuery()
 *   GET  /trending                                   → handleGetTrending()
 *   GET  /stats                                      → handleGetStats()
 *
 * Wiring:
 *   AppConfig → creates AutocompleteController(autocompleteService)
 *   Main app calls controller methods to simulate user interactions
 */
public class AutocompleteController {

    private final AutocompleteService autocompleteService;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public AutocompleteController(AutocompleteService autocompleteService) {
        this.autocompleteService = autocompleteService;
    }

    // -----------------------------------------------------------------------
    // Endpoint: Search / Autocomplete
    // -----------------------------------------------------------------------

    /**
     * Simulate: GET /autocomplete?q={prefix}&userId={userId}
     *
     * Returns autocomplete suggestions for the given prefix.
     * Creates a SearchContext from the request parameters.
     *
     * @param prefix the text the user has typed so far
     * @param userId the authenticated user (null for anonymous)
     * @return formatted response string with suggestions
     */
    public String handleSearch(String prefix, String userId) {
        SearchContext context = new SearchContext(userId, "en", "US", LocalDateTime.now());
        List<Suggestion> suggestions = autocompleteService.getSuggestions(prefix, context);

        StringBuilder response = new StringBuilder();
        response.append(String.format("Autocomplete for \"%s\"", prefix));
        if (userId != null) {
            response.append(String.format(" (user: %s)", userId));
        }
        response.append(":\n");

        if (suggestions.isEmpty()) {
            response.append("  (no suggestions)\n");
        } else {
            for (int i = 0; i < suggestions.size(); i++) {
                Suggestion s = suggestions.get(i);
                response.append(String.format("  %d. %-30s [score: %.2f, source: %s]\n",
                        i + 1, s.getText(), s.getScore(), s.getSource()));
            }
        }

        return response.toString();
    }

    // -----------------------------------------------------------------------
    // Endpoint: Record Query
    // -----------------------------------------------------------------------

    /**
     * Simulate: POST /query?q={queryText}&userId={userId}
     *
     * Records that a user submitted a search query.
     * This feeds the data collection pipeline.
     *
     * @param queryText the full query text submitted
     * @param userId    the user who submitted (null for anonymous)
     * @return confirmation message
     */
    public String handleRecordQuery(String queryText, String userId) {
        autocompleteService.recordQuery(queryText, userId);
        return String.format("Recorded query: \"%s\" (user: %s)", queryText,
                userId != null ? userId : "anonymous");
    }

    // -----------------------------------------------------------------------
    // Endpoint: Trending Queries
    // -----------------------------------------------------------------------

    /**
     * Simulate: GET /trending
     *
     * Returns currently trending queries.
     */
    public String handleGetTrending() {
        List<SearchQuery> trending = autocompleteService.getDataCollectionService()
                .getTrendingQueries(10, 1);

        StringBuilder response = new StringBuilder();
        response.append("Trending Queries:\n");

        if (trending.isEmpty()) {
            response.append("  (no trending queries detected)\n");
        } else {
            for (int i = 0; i < trending.size(); i++) {
                SearchQuery q = trending.get(i);
                response.append(String.format("  %d. %-30s [recent freq: %d]\n",
                        i + 1, q.getQueryText(), q.getFrequency()));
            }
        }

        return response.toString();
    }

    // -----------------------------------------------------------------------
    // Endpoint: Stats
    // -----------------------------------------------------------------------

    /**
     * Simulate: GET /stats
     *
     * Returns system statistics.
     */
    public String handleGetStats() {
        StringBuilder response = new StringBuilder();
        response.append("Autocomplete System Stats:\n");
        response.append(String.format("  Trie size:           %d words\n",
                autocompleteService.getTrieService().size()));
        response.append(String.format("  Cache size:          %d entries\n",
                autocompleteService.getCache().size()));
        response.append(String.format("  Cache hit rate:      %.2f%%\n",
                autocompleteService.getCache().getHitRate() * 100));
        response.append(String.format("  Total queries:       %d\n",
                autocompleteService.getDataCollectionService().getTotalQueriesRecorded()));
        response.append(String.format("  Unique queries:      %d\n",
                autocompleteService.getDataCollectionService().getUniqueQueryCount()));
        response.append(String.format("  Ranking strategy:    %s\n",
                autocompleteService.getRankingService().getActiveStrategyName()));
        return response.toString();
    }

    /**
     * Get the underlying service (for direct access in demos).
     */
    public AutocompleteService getAutocompleteService() {
        return autocompleteService;
    }
}
