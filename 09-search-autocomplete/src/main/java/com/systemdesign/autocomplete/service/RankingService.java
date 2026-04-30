package com.systemdesign.autocomplete.service;

import com.systemdesign.autocomplete.model.SearchContext;
import com.systemdesign.autocomplete.model.Suggestion;
import com.systemdesign.autocomplete.strategy.ranking.RankingStrategy;

import java.util.List;

/**
 * RankingService — Delegates suggestion ranking to the configured RankingStrategy.
 *
 * WHY a service wrapper over the strategy?
 * -----------------------------------------
 * 1. Runtime strategy switching: can change ranking algorithm without restart
 *    (e.g., A/B testing: 50% of users get frequency ranking, 50% get time-decay)
 * 2. Logging/metrics: can log which strategy is used, timing, etc.
 * 3. Fallback: if the configured strategy throws, can fall back to a default
 * 4. Clean separation: AutocompleteService doesn't need to know which strategy is active
 *
 * Wiring:
 *   AppConfig → creates RankingService(strategy) → injects into AutocompleteService
 *   AutocompleteService.getSuggestions():
 *     raw suggestions from trie → RankingService.rankSuggestions() → ranked suggestions
 */
public class RankingService {

    /** The currently active ranking strategy. */
    private RankingStrategy strategy;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * @param strategy the initial ranking strategy (injected by AppConfig)
     */
    public RankingService(RankingStrategy strategy) {
        this.strategy = strategy;
    }

    // -----------------------------------------------------------------------
    // Core method
    // -----------------------------------------------------------------------

    /**
     * Rank suggestions using the active strategy.
     *
     * @param suggestions raw suggestions from the trie
     * @param context     search context (user info, timestamp, etc.)
     * @return ranked/re-scored suggestions
     */
    public List<Suggestion> rankSuggestions(List<Suggestion> suggestions, SearchContext context) {
        if (suggestions == null || suggestions.isEmpty()) {
            return suggestions;
        }
        return strategy.rank(suggestions, context);
    }

    // -----------------------------------------------------------------------
    // Runtime strategy switching
    // -----------------------------------------------------------------------

    /**
     * Switch the ranking strategy at runtime.
     *
     * Use case: A/B testing, switching from frequency to time-decay
     * during a live event (election night, Super Bowl, etc.)
     *
     * @param newStrategy the new strategy to use
     */
    public void setStrategy(RankingStrategy newStrategy) {
        if (newStrategy == null) {
            throw new IllegalArgumentException("RankingStrategy cannot be null");
        }
        this.strategy = newStrategy;
    }

    /**
     * Get the name of the current strategy (for logging/display).
     */
    public String getActiveStrategyName() {
        return strategy.getName();
    }

    /**
     * Get the current strategy (for testing/display).
     */
    public RankingStrategy getStrategy() {
        return strategy;
    }
}
