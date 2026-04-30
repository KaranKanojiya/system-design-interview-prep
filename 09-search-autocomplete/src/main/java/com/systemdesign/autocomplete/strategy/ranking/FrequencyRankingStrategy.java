package com.systemdesign.autocomplete.strategy.ranking;

import com.systemdesign.autocomplete.model.SearchContext;
import com.systemdesign.autocomplete.model.Suggestion;

import java.util.Collections;
import java.util.List;

/**
 * FrequencyRankingStrategy — Rank suggestions purely by raw search frequency.
 *
 * The simplest ranking strategy. Score = frequency. Higher frequency = more relevant.
 *
 * WHEN TO USE:
 *   - Anonymous users (no personalization data)
 *   - Cold-start scenarios (no time-decay data yet)
 *   - Baseline comparison for A/B testing
 *
 * Ugly approach (what you'd see in a rushed codebase):
 *
 *   // Inline sorting scattered across multiple methods
 *   public List<Suggestion> getSuggestions(String prefix) {
 *       List<Suggestion> results = trie.getSuggestions(prefix, 10);
 *       // Hardcoded sort logic — can't swap for time-decay or personalized
 *       if (results != null && !results.isEmpty()) {
 *           for (int i = 0; i < results.size() - 1; i++) {
 *               for (int j = i + 1; j < results.size(); j++) {
 *                   if (results.get(j).getScore() > results.get(i).getScore()) {
 *                       Suggestion temp = results.get(i);
 *                       results.set(i, results.get(j));
 *                       results.set(j, temp);
 *                   }
 *               }
 *           }
 *       }
 *       return results;
 *   }
 *   // Bubble sort? Really? And duplicated in 3 other places? Yikes.
 *
 * Clean approach (this class):
 *   - Strategy pattern: single rank() method, no duplication
 *   - Uses Comparable (Suggestion implements it) or Collections.sort
 *   - Easy to swap for another strategy at runtime
 *
 * Wiring:
 *   AppConfig → new FrequencyRankingStrategy() → RankingService → AutocompleteService
 */
public class FrequencyRankingStrategy implements RankingStrategy {

    /**
     * Rank by raw frequency. Score is already set to frequency by the Trie,
     * so we just need to sort.
     *
     * If scores weren't already set, we'd iterate and set them:
     *   for (Suggestion s : suggestions) {
     *       s.setScore(s.getScore()); // score == frequency from trie
     *   }
     *
     * But since Trie already sets score = frequency on Suggestion creation,
     * we just sort using the natural ordering (Comparable: score desc).
     */
    @Override
    public List<Suggestion> rank(List<Suggestion> suggestions, SearchContext context) {
        if (suggestions == null || suggestions.isEmpty()) {
            return suggestions;
        }

        // Suggestion implements Comparable: sorts by score descending, then alphabetical
        Collections.sort(suggestions);
        return suggestions;
    }

    @Override
    public String getName() {
        return "FrequencyRanking";
    }
}
