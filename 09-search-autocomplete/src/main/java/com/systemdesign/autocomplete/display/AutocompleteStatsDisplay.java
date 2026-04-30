package com.systemdesign.autocomplete.display;

import com.systemdesign.autocomplete.model.SearchQuery;
import com.systemdesign.autocomplete.service.AutocompleteService;
import com.systemdesign.autocomplete.service.DataCollectionService;
import com.systemdesign.autocomplete.store.SuggestionCache;

import java.util.List;

/**
 * AutocompleteStatsDisplay — Renders system statistics to the console.
 *
 * Shows:
 *   - Total queries indexed in the trie
 *   - Cache hit rate and size
 *   - Trie size (number of words)
 *   - Top queries by frequency
 *   - Trending queries
 *   - Active ranking strategy
 *
 * Wiring:
 *   Main app → new AutocompleteStatsDisplay(autocompleteService) → display.showStats()
 */
public class AutocompleteStatsDisplay {

    private static final String SEPARATOR = "=".repeat(70);
    private static final String THIN_SEP = "-".repeat(70);

    private final AutocompleteService autocompleteService;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public AutocompleteStatsDisplay(AutocompleteService autocompleteService) {
        this.autocompleteService = autocompleteService;
    }

    // -----------------------------------------------------------------------
    // Display methods
    // -----------------------------------------------------------------------

    /**
     * Display comprehensive system statistics.
     */
    public void showStats() {
        System.out.println(SEPARATOR);
        System.out.println("        AUTOCOMPLETE SYSTEM STATISTICS");
        System.out.println(SEPARATOR);

        showTrieStats();
        showCacheStats();
        showQueryStats();
        showTopQueries(10);
        showTrendingQueries(5);

        System.out.println(SEPARATOR);
    }

    /**
     * Display trie-related statistics.
     */
    public void showTrieStats() {
        System.out.println(THIN_SEP);
        System.out.println("  TRIE STATS");
        System.out.println(THIN_SEP);
        System.out.printf("  Words indexed:        %d%n", autocompleteService.getTrieService().size());
        System.out.printf("  Ranking strategy:     %s%n",
                autocompleteService.getRankingService().getActiveStrategyName());
    }

    /**
     * Display cache statistics.
     */
    public void showCacheStats() {
        SuggestionCache cache = autocompleteService.getCache();
        System.out.println(THIN_SEP);
        System.out.println("  CACHE STATS");
        System.out.println(THIN_SEP);
        System.out.printf("  Cache size:           %d entries%n", cache.size());
        System.out.printf("  Cache hit rate:       %.2f%%%n", cache.getHitRate() * 100);
    }

    /**
     * Display query collection statistics.
     */
    public void showQueryStats() {
        DataCollectionService dcs = autocompleteService.getDataCollectionService();
        System.out.println(THIN_SEP);
        System.out.println("  QUERY STATS");
        System.out.println(THIN_SEP);
        System.out.printf("  Total queries logged: %d%n", dcs.getTotalQueriesRecorded());
        System.out.printf("  Unique queries:       %d%n", dcs.getUniqueQueryCount());
    }

    /**
     * Display the top-K most popular queries.
     */
    public void showTopQueries(int limit) {
        DataCollectionService dcs = autocompleteService.getDataCollectionService();
        List<SearchQuery> topQueries = dcs.getTopQueries(limit);

        System.out.println(THIN_SEP);
        System.out.printf("  TOP %d QUERIES%n", limit);
        System.out.println(THIN_SEP);

        if (topQueries.isEmpty()) {
            System.out.println("  (no queries recorded yet)");
        } else {
            for (int i = 0; i < topQueries.size(); i++) {
                SearchQuery q = topQueries.get(i);
                System.out.printf("  %2d. %-35s [freq: %d]%n",
                        i + 1, q.getQueryText(), q.getFrequency());
            }
        }
    }

    /**
     * Display currently trending queries.
     */
    public void showTrendingQueries(int limit) {
        DataCollectionService dcs = autocompleteService.getDataCollectionService();
        List<SearchQuery> trending = dcs.getTrendingQueries(limit, 1);

        System.out.println(THIN_SEP);
        System.out.println("  TRENDING QUERIES (last 1 hour)");
        System.out.println(THIN_SEP);

        if (trending.isEmpty()) {
            System.out.println("  (no trending queries detected)");
        } else {
            for (int i = 0; i < trending.size(); i++) {
                SearchQuery q = trending.get(i);
                System.out.printf("  %2d. %-35s [recent freq: %d]%n",
                        i + 1, q.getQueryText(), q.getFrequency());
            }
        }
    }
}
