package com.systemdesign.autocomplete.strategy.ranking;

import com.systemdesign.autocomplete.model.SearchContext;
import com.systemdesign.autocomplete.model.Suggestion;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PersonalizedRankingStrategy — Decorator that boosts suggestions based on user history.
 *
 * DECORATOR PATTERN:
 * ------------------
 * This wraps another RankingStrategy (e.g., FrequencyRanking or TimeDecayRanking)
 * and adds personalization ON TOP of the base ranking.
 *
 * WHY Decorator?
 *   - Personalization is an ADDITIVE concern, not a replacement
 *   - You want: "apply time-decay THEN boost for this user's history"
 *   - Decorator lets you compose: Personalized(TimeDecay(base))
 *   - Adding/removing personalization doesn't change the base strategy code
 *
 * BOOST FACTORS:
 *   - 1.5x if the user has previously searched this exact query
 *     (strong signal: they liked this result enough to search it again)
 *   - 1.2x if the suggestion matches the user's language or location
 *     (weaker signal: geographic/linguistic relevance)
 *
 * Example:
 *   User "alice" previously searched "java interview questions"
 *   Suggestions for "java":
 *     "java tutorial"         → score 1000 (no boost)
 *     "java interview questions" → score 800 * 1.5 = 1200 (user history boost!)
 *     "javascript"            → score 900 (no boost)
 *   After personalization: "java interview questions" (1200) > "java tutorial" (1000)
 *
 * Wiring:
 *   AppConfig → new PersonalizedRankingStrategy(
 *       new TimeDecayRankingStrategy(0.01)  // base strategy
 *   ) → RankingService
 *
 *   Call chain:
 *     RankingService.rank()
 *       → PersonalizedRankingStrategy.rank()
 *         → base.rank() (TimeDecay applies first)
 *         → then apply personalization boosts
 *         → re-sort
 */
public class PersonalizedRankingStrategy implements RankingStrategy {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Boost multiplier for queries the user has searched before. */
    private static final double USER_HISTORY_BOOST = 1.5;

    /** Boost multiplier for suggestions matching user's language/location. */
    private static final double LOCALE_BOOST = 1.2;

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /**
     * The base ranking strategy to decorate.
     * Personalization is applied AFTER the base ranking.
     */
    private final RankingStrategy baseStrategy;

    /**
     * Per-user search history: userId → Set of previously searched queries.
     * In production, this would be backed by a user profile service or database.
     */
    private final Map<String, Set<String>> userSearchHistory;

    /**
     * Per-user locale preferences: userId → language code.
     * Example: "alice" → "en", "tanaka" → "ja"
     */
    private final Map<String, String> userLanguages;

    /**
     * Per-user location: userId → location code.
     * Example: "alice" → "US", "bob" → "UK"
     */
    private final Map<String, String> userLocations;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * @param baseStrategy the underlying ranking strategy to decorate
     */
    public PersonalizedRankingStrategy(RankingStrategy baseStrategy) {
        this.baseStrategy = baseStrategy;
        this.userSearchHistory = new ConcurrentHashMap<>();
        this.userLanguages = new ConcurrentHashMap<>();
        this.userLocations = new ConcurrentHashMap<>();
    }

    // -----------------------------------------------------------------------
    // rank() — The Decorator's core method
    // -----------------------------------------------------------------------

    /**
     * Apply personalization on top of the base ranking.
     *
     * Flow:
     *   1. Delegate to baseStrategy.rank() — applies frequency/time-decay/etc.
     *   2. If user is authenticated:
     *      a. Boost suggestions that the user has searched before (1.5x)
     *      b. Boost suggestions matching user's language/location (1.2x)
     *   3. Re-sort by updated scores
     *
     * If user is anonymous (no userId in context), just return base ranking as-is.
     */
    @Override
    public List<Suggestion> rank(List<Suggestion> suggestions, SearchContext context) {
        // Step 1: Apply base ranking first
        List<Suggestion> ranked = baseStrategy.rank(suggestions, context);

        // Step 2: Apply personalization (only for authenticated users)
        if (context == null || !context.isAuthenticated()) {
            return ranked; // No personalization for anonymous users
        }

        String userId = context.getUserId();
        Set<String> history = userSearchHistory.get(userId);
        String userLang = userLanguages.getOrDefault(userId, "en");
        String userLoc = userLocations.getOrDefault(userId, "US");

        for (Suggestion suggestion : ranked) {
            double boost = 1.0;

            // Boost 1: User has searched this exact query before → strong signal
            if (history != null && history.contains(suggestion.getText())) {
                boost *= USER_HISTORY_BOOST;
            }

            // Boost 2: Suggestion matches user's language/location → weaker signal
            // In production, suggestions would have locale metadata.
            // Here we simulate: if the context's language matches user preference, boost.
            if (context.getLanguage() != null && context.getLanguage().equalsIgnoreCase(userLang)) {
                boost *= LOCALE_BOOST;
            }

            // Apply combined boost
            if (boost > 1.0) {
                suggestion.setScore(suggestion.getScore() * boost);
            }
        }

        // Re-sort after boosting
        Collections.sort(ranked);
        return ranked;
    }

    // -----------------------------------------------------------------------
    // User data management
    // -----------------------------------------------------------------------

    /**
     * Record that a user searched for a query.
     * Called by DataCollectionService when a search is performed.
     */
    public void recordUserSearch(String userId, String queryText) {
        if (userId == null || queryText == null) return;
        userSearchHistory
                .computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(queryText.toLowerCase().trim());
    }

    /**
     * Set a user's language preference.
     */
    public void setUserLanguage(String userId, String language) {
        if (userId != null && language != null) {
            userLanguages.put(userId, language);
        }
    }

    /**
     * Set a user's location.
     */
    public void setUserLocation(String userId, String location) {
        if (userId != null && location != null) {
            userLocations.put(userId, location);
        }
    }

    /**
     * Check if a user has searched a specific query before.
     */
    public boolean hasUserSearched(String userId, String queryText) {
        Set<String> history = userSearchHistory.get(userId);
        return history != null && history.contains(queryText.toLowerCase().trim());
    }

    @Override
    public String getName() {
        return "PersonalizedRanking(base=" + baseStrategy.getName() + ")";
    }
}
