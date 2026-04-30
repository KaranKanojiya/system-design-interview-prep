package com.systemdesign.autocomplete.strategy.ranking;

import com.systemdesign.autocomplete.model.SearchContext;
import com.systemdesign.autocomplete.model.Suggestion;

import java.util.List;

/**
 * RankingStrategy — Interface for different suggestion ranking algorithms.
 *
 * WHY Strategy pattern?
 * ---------------------
 * Different ranking algorithms are needed for different scenarios:
 *   - FrequencyRanking: simple popularity (good for anonymous users)
 *   - TimeDecayRanking: freshness matters (news, trending topics)
 *   - PersonalizedRanking: user-specific boosting (logged-in users)
 *
 * Ugly approach:
 *   // Giant if-else in the service layer
 *   if (rankingType.equals("frequency")) {
 *       suggestions.sort((a, b) -> Long.compare(b.freq, a.freq));
 *   } else if (rankingType.equals("timeDecay")) {
 *       for (Suggestion s : suggestions) {
 *           double hours = ChronoUnit.HOURS.between(s.lastSearched, now);
 *           s.setScore(s.getFreq() * Math.exp(-0.01 * hours));
 *       }
 *       suggestions.sort((a, b) -> Double.compare(b.score, a.score));
 *   } else if (rankingType.equals("personalized")) {
 *       // Even more spaghetti...
 *   }
 *   // Adding a new strategy = modifying this if-else = violates Open/Closed Principle
 *
 * Clean approach (Strategy pattern):
 *   rankingStrategy.rank(suggestions, context);
 *   // Strategy is injected, swappable at runtime, easy to add new ones
 *
 * Wiring:
 *   AppConfig → creates RankingStrategy implementation → injects into RankingService
 *   RankingService.rankSuggestions() → delegates to RankingStrategy.rank()
 *   AutocompleteService → calls RankingService after getting raw suggestions from Trie
 */
public interface RankingStrategy {

    /**
     * Rank/re-score a list of suggestions based on the given context.
     *
     * @param suggestions the raw suggestions from the trie (scored by raw frequency)
     * @param context     search context (user info, timestamp, location, etc.)
     * @return re-ranked list of suggestions (same objects, modified scores, re-sorted)
     */
    List<Suggestion> rank(List<Suggestion> suggestions, SearchContext context);

    /**
     * Human-readable name for this strategy (for logging/display).
     */
    String getName();
}
