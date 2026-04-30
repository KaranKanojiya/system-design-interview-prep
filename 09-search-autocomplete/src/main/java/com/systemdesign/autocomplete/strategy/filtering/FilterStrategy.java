package com.systemdesign.autocomplete.strategy.filtering;

import com.systemdesign.autocomplete.model.Suggestion;

import java.util.List;

/**
 * FilterStrategy — Interface for filtering inappropriate or unwanted suggestions.
 *
 * WHY a separate filter interface?
 * ---------------------------------
 * Filtering is a distinct concern from ranking. Ranking decides ORDER; filtering decides INCLUSION.
 * Separating them follows Single Responsibility Principle and allows chaining multiple filters:
 *
 *   Chain of Responsibility:
 *     raw suggestions → ProfanityFilter → SpamFilter → MinScoreFilter → final suggestions
 *
 * Each filter removes suggestions that fail its criteria, passing the rest downstream.
 *
 * Ugly approach:
 *   // All filtering logic crammed into the service method
 *   List<Suggestion> getSuggestions(String prefix) {
 *       List<Suggestion> results = trie.getSuggestions(prefix, 10);
 *       Iterator<Suggestion> it = results.iterator();
 *       while (it.hasNext()) {
 *           Suggestion s = it.next();
 *           if (bannedWords.contains(s.getText())) it.remove();      // profanity
 *           else if (s.getText().contains("buy now")) it.remove();   // spam
 *           else if (s.getScore() < 5) it.remove();                  // low quality
 *       }
 *       return results;
 *   }
 *   // Adding a new filter type = modifying this method = violates Open/Closed
 *
 * Clean approach (this interface):
 *   filters.forEach(filter -> filter.filter(suggestions));
 *   // Each filter is independent, composable, testable
 *
 * Wiring:
 *   AppConfig → creates List<FilterStrategy> → AutocompleteService applies them in order
 */
public interface FilterStrategy {

    /**
     * Filter suggestions, removing any that don't meet this strategy's criteria.
     *
     * @param suggestions the list to filter (may be modified in place or a new list returned)
     * @return the filtered list (may be smaller than the input)
     */
    List<Suggestion> filter(List<Suggestion> suggestions);

    /**
     * Human-readable name for this filter (for logging/display).
     */
    String getName();
}
