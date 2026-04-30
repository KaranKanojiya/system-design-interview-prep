package com.systemdesign.autocomplete.strategy.ranking;

import com.systemdesign.autocomplete.model.SearchContext;
import com.systemdesign.autocomplete.model.Suggestion;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TimeDecayRankingStrategy — Rank suggestions using exponential time decay.
 *
 * FORMULA:
 *   score = frequency * exp(-lambda * hoursAge)
 *
 * WHERE:
 *   frequency = raw search count for this query
 *   lambda    = decay factor (configurable, default 0.01)
 *   hoursAge  = hours since this query was last searched
 *
 * INTUITION:
 *   - A query searched 1000 times but not in the last week should rank lower
 *     than a query searched 500 times in the last hour.
 *   - Exponential decay models the "freshness" of a query.
 *   - lambda controls how fast old queries lose relevance:
 *       lambda = 0.01 → half-life ≈ 69 hours (~3 days)
 *       lambda = 0.1  → half-life ≈ 7 hours (aggressive — for breaking news)
 *       lambda = 0.001 → half-life ≈ 693 hours (~29 days — very stable)
 *
 * HALF-LIFE DERIVATION:
 *   score(t) = freq * exp(-lambda * t)
 *   Half-life = when score drops to 50% of initial:
 *     0.5 = exp(-lambda * t_half)
 *     ln(0.5) = -lambda * t_half
 *     t_half = ln(2) / lambda ≈ 0.693 / lambda
 *   For lambda = 0.01: t_half = 0.693 / 0.01 = 69.3 hours ≈ 3 days
 *
 * EXAMPLE:
 *   Query "super bowl" searched 10000 times, last searched 72 hours ago:
 *     score = 10000 * exp(-0.01 * 72) = 10000 * 0.487 = 4868
 *
 *   Query "nfl draft" searched 3000 times, last searched 2 hours ago:
 *     score = 3000 * exp(-0.01 * 2) = 3000 * 0.980 = 2940
 *
 *   Without decay: "super bowl" (10000) > "nfl draft" (3000)
 *   With decay: "super bowl" (4868) > "nfl draft" (2940)  — still wins but gap is smaller
 *   With aggressive decay (lambda=0.1):
 *     "super bowl" = 10000 * exp(-0.1*72) = 7.5 (buried!)
 *     "nfl draft"  = 3000 * exp(-0.1*2) = 2459 (wins easily!)
 *
 * Wiring:
 *   AppConfig → new TimeDecayRankingStrategy(config.getDecayFactor()) → RankingService
 */
public class TimeDecayRankingStrategy implements RankingStrategy {

    /** Decay factor lambda. See class javadoc for how this affects half-life. */
    private final double decayFactor;

    /**
     * Track when each query was last searched.
     * In production, this would come from the SearchQuery model via the repository.
     * Here we maintain an in-memory map for simplicity.
     */
    private final Map<String, LocalDateTime> lastSearchedMap;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * @param decayFactor the lambda in exp(-lambda * hours). Default: 0.01
     */
    public TimeDecayRankingStrategy(double decayFactor) {
        if (decayFactor < 0) {
            throw new IllegalArgumentException("decayFactor must be >= 0");
        }
        this.decayFactor = decayFactor;
        this.lastSearchedMap = new ConcurrentHashMap<>();
    }

    // -----------------------------------------------------------------------
    // rank()
    // -----------------------------------------------------------------------

    /**
     * Apply time-decay to each suggestion's score, then re-sort.
     *
     * For each suggestion:
     *   1. Look up when it was last searched
     *   2. Calculate hours since then
     *   3. Apply formula: newScore = oldScore * exp(-lambda * hours)
     *   4. Update the suggestion's score
     *
     * Then sort all suggestions by their new (decayed) scores.
     */
    @Override
    public List<Suggestion> rank(List<Suggestion> suggestions, SearchContext context) {
        if (suggestions == null || suggestions.isEmpty()) {
            return suggestions;
        }

        LocalDateTime now = context.getTimestamp() != null ? context.getTimestamp() : LocalDateTime.now();

        for (Suggestion suggestion : suggestions) {
            LocalDateTime lastSearched = lastSearchedMap.getOrDefault(
                    suggestion.getText(), now.minusHours(1) // default: 1 hour ago
            );

            // Calculate hours since last search
            double hoursSinceLastSearch = Duration.between(lastSearched, now).toMinutes() / 60.0;
            if (hoursSinceLastSearch < 0) {
                hoursSinceLastSearch = 0; // guard against clock skew
            }

            // Apply time-decay formula:
            //   score = frequency * exp(-lambda * hoursAge)
            double decayedScore = suggestion.getScore() * Math.exp(-decayFactor * hoursSinceLastSearch);
            suggestion.setScore(decayedScore);
        }

        // Re-sort by decayed scores
        Collections.sort(suggestions);
        return suggestions;
    }

    // -----------------------------------------------------------------------
    // Data management
    // -----------------------------------------------------------------------

    /**
     * Record when a query was last searched.
     * Called by DataCollectionService when a user performs a search.
     *
     * @param queryText    the query text
     * @param searchedAt   when it was searched
     */
    public void recordSearch(String queryText, LocalDateTime searchedAt) {
        if (queryText != null && !queryText.isBlank()) {
            lastSearchedMap.put(queryText.toLowerCase().trim(), searchedAt);
        }
    }

    /**
     * Get the decay factor for display/debugging.
     */
    public double getDecayFactor() {
        return decayFactor;
    }

    /**
     * Calculate the half-life in hours for this decay factor.
     * Half-life = ln(2) / lambda
     */
    public double getHalfLifeHours() {
        if (decayFactor == 0) return Double.POSITIVE_INFINITY;
        return Math.log(2) / decayFactor;
    }

    @Override
    public String getName() {
        return String.format("TimeDecayRanking(lambda=%.4f, halfLife=%.1fh)", decayFactor, getHalfLifeHours());
    }
}
