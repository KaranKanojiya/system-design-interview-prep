package com.systemdesign.autocomplete.model;

/**
 * Suggestion — A single autocomplete suggestion returned to the user.
 *
 * WHY Comparable?
 * ----------------
 * Suggestions must be sorted by score (descending) before returning to the user.
 * Implementing Comparable gives us natural ordering that works with:
 *   - Collections.sort(suggestions)
 *   - TreeSet / PriorityQueue
 *   - Stream.sorted()
 *
 * Ugly approach:
 *   // Sorting with ad-hoc comparator everywhere
 *   suggestions.sort((a, b) -> Double.compare(b.score, a.score));
 *   // Duplicated in 5 places, easy to get the order wrong (a,b vs b,a)
 *
 * Clean approach:
 *   // Natural ordering via Comparable, defined ONCE
 *   Collections.sort(suggestions); // just works, score DESC
 *
 * SuggestionSource enum encodes WHERE this suggestion came from:
 *   - POPULAR: high global frequency
 *   - TRENDING: frequency spike in recent time window
 *   - PERSONALIZED: boosted for this specific user
 *   - AUTOCORRECT: fuzzy match / spelling correction
 *   This is useful for UI — e.g., show a "trending" icon next to trending suggestions.
 *
 * Wiring:
 *   Trie.getSuggestions() → creates Suggestion objects
 *   RankingStrategy.rank() → adjusts scores, re-sorts
 *   FilterStrategy.filter() → removes inappropriate suggestions
 *   AutocompleteService → returns final List<Suggestion> to controller
 */
public class Suggestion implements Comparable<Suggestion> {

    // -----------------------------------------------------------------------
    // Source enum
    // -----------------------------------------------------------------------

    /**
     * Where this suggestion came from. Useful for:
     *   1. UI rendering (show icons for trending, personalized)
     *   2. Analytics (track which source users click on most)
     *   3. A/B testing different suggestion sources
     */
    public enum SuggestionSource {
        POPULAR,       // High global search frequency
        TRENDING,      // Recent frequency spike
        PERSONALIZED,  // Boosted for this user based on history
        AUTOCORRECT    // Fuzzy/spelling-corrected match
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** The suggestion text shown to the user (e.g., "apple watch"). */
    private final String text;

    /**
     * Ranking score. Higher = more relevant.
     * This score is computed by the RankingStrategy and may incorporate:
     *   - Raw frequency
     *   - Time decay
     *   - Personalization boost
     *   - Source-specific weighting
     */
    private double score;

    /** Where this suggestion originated from. */
    private final SuggestionSource source;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public Suggestion(String text, double score, SuggestionSource source) {
        this.text = text;
        this.score = score;
        this.source = source;
    }

    /**
     * Convenience constructor — defaults to POPULAR source.
     * Used when building suggestions from raw trie frequency data.
     */
    public Suggestion(String text, double score) {
        this(text, score, SuggestionSource.POPULAR);
    }

    // -----------------------------------------------------------------------
    // Comparable — sort by score DESCENDING (highest score first)
    // -----------------------------------------------------------------------

    /**
     * Natural ordering: higher score comes first.
     *
     * WHY descending? Autocomplete always shows the most relevant suggestions first.
     * If scores are equal, sort alphabetically (ascending) for deterministic output.
     *
     * compareTo contract:
     *   negative → this comes BEFORE other (this is "less than" in sorted order)
     *   positive → this comes AFTER other
     *   zero → equal
     *
     * We flip the score comparison (other.score - this.score) for descending order.
     */
    @Override
    public int compareTo(Suggestion other) {
        // Primary: score descending (higher score = earlier in list)
        int scoreCompare = Double.compare(other.score, this.score);
        if (scoreCompare != 0) {
            return scoreCompare;
        }
        // Secondary: alphabetical ascending (for deterministic output)
        return this.text.compareTo(other.text);
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public String getText() {
        return text;
    }

    public double getScore() {
        return score;
    }

    /**
     * Score can be updated by ranking strategies.
     * E.g., PersonalizedRankingStrategy boosts score by 1.5x for user's past queries.
     */
    public void setScore(double score) {
        this.score = score;
    }

    public SuggestionSource getSource() {
        return source;
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return String.format("Suggestion{text='%s', score=%.2f, source=%s}", text, score, source);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Suggestion that = (Suggestion) o;
        return text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }
}
