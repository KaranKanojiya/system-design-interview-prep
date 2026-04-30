package com.systemdesign.autocomplete.model;

import java.time.LocalDateTime;

/**
 * SearchContext — Contextual information about the current search request.
 *
 * WHY a separate context object?
 * ------------------------------
 * Interview Insight: Passing individual parameters (userId, language, location, timestamp)
 * through every method creates fragile, hard-to-extend signatures.
 *
 * Ugly approach:
 *   List<Suggestion> getSuggestions(String prefix, String userId, String lang,
 *                                    String location, LocalDateTime ts) { ... }
 *   // Adding a new context field = changing every method signature in the call chain!
 *
 * Clean approach (Parameter Object pattern):
 *   List<Suggestion> getSuggestions(String prefix, SearchContext context) { ... }
 *   // Adding a new field = add it to SearchContext, update only the strategies that use it
 *
 * Wiring:
 *   AutocompleteController.handleSearch() → creates SearchContext
 *   → AutocompleteService.getSuggestions(prefix, context)
 *     → RankingService.rankSuggestions(suggestions, context)
 *       → PersonalizedRankingStrategy.rank(suggestions, context) — uses userId, language, location
 *       → TimeDecayRankingStrategy.rank(suggestions, context) — uses timestamp
 */
public class SearchContext {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /**
     * The user making the search. Null for anonymous users.
     * Used by PersonalizedRankingStrategy to boost previously-searched queries.
     */
    private final String userId;

    /**
     * User's language preference (e.g., "en", "ja", "es").
     * Used to boost suggestions matching the user's language.
     */
    private final String language;

    /**
     * User's geographic location (e.g., "US", "JP", "UK").
     * Used to boost region-specific suggestions (e.g., "cricket" in UK/India vs US).
     */
    private final String location;

    /**
     * When this search was performed.
     * Used by TimeDecayRankingStrategy to calculate how "fresh" results are.
     */
    private final LocalDateTime timestamp;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public SearchContext(String userId, String language, String location, LocalDateTime timestamp) {
        this.userId = userId;
        this.language = language;
        this.location = location;
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
    }

    /**
     * Convenience constructor for anonymous searches with defaults.
     */
    public SearchContext() {
        this(null, "en", "US", LocalDateTime.now());
    }

    /**
     * Convenience constructor for a known user with defaults.
     */
    public SearchContext(String userId) {
        this(userId, "en", "US", LocalDateTime.now());
    }

    // -----------------------------------------------------------------------
    // Getters
    // -----------------------------------------------------------------------

    public String getUserId() {
        return userId;
    }

    public String getLanguage() {
        return language;
    }

    public String getLocation() {
        return location;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    /**
     * Whether this is an authenticated (non-anonymous) search.
     * Personalization only applies to authenticated users.
     */
    public boolean isAuthenticated() {
        return userId != null && !userId.isBlank();
    }

    @Override
    public String toString() {
        return "SearchContext{" +
                "userId='" + userId + '\'' +
                ", language='" + language + '\'' +
                ", location='" + location + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
