package com.systemdesign.autocomplete.model;

import java.time.LocalDateTime;

/**
 * SearchQuery — Represents a user's search query with metadata.
 *
 * WHY Builder pattern?
 * --------------------
 * Interview Insight: Constructors with many optional parameters are a code smell.
 *
 * Ugly approach (telescoping constructors):
 *   SearchQuery(String text) { ... }
 *   SearchQuery(String text, long freq) { ... }
 *   SearchQuery(String text, long freq, LocalDateTime ts) { ... }
 *   SearchQuery(String text, long freq, LocalDateTime ts, String userId) { ... }
 *   // Combinatorial explosion! And the caller can't tell what each arg means:
 *   // new SearchQuery("apple", 42, null, "user123") — what's 42? what's null?
 *
 * Clean approach (Builder):
 *   SearchQuery.builder("apple")
 *       .frequency(42)
 *       .userId("user123")
 *       .build();
 *   // Self-documenting, order-independent, optional params are truly optional
 *
 * Wiring:
 *   DataCollectionService.recordQuery() → creates SearchQuery → saves to QueryRepository
 *   TrieBuilderService.buildTrie() → reads SearchQuery from repository → inserts into Trie
 */
public class SearchQuery {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** The actual text the user typed (e.g., "how to reverse a linked list"). */
    private final String queryText;

    /** How many times this exact query has been searched globally. */
    private final long frequency;

    /** When this query was last searched. Used for time-decay ranking. */
    private final LocalDateTime lastSearched;

    /**
     * The user who searched this query. Nullable — anonymous searches don't have a userId.
     * Used for personalized ranking: "boost queries this user has searched before."
     */
    private final String userId;

    // -----------------------------------------------------------------------
    // Private constructor — only Builder can create instances
    // -----------------------------------------------------------------------

    private SearchQuery(Builder builder) {
        this.queryText = builder.queryText;
        this.frequency = builder.frequency;
        this.lastSearched = builder.lastSearched;
        this.userId = builder.userId;
    }

    // -----------------------------------------------------------------------
    // Getters (no setters — SearchQuery is immutable once built)
    // -----------------------------------------------------------------------

    public String getQueryText() {
        return queryText;
    }

    public long getFrequency() {
        return frequency;
    }

    public LocalDateTime getLastSearched() {
        return lastSearched;
    }

    public String getUserId() {
        return userId;
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    /**
     * Factory method for creating a Builder. queryText is required.
     *
     * Usage:
     *   SearchQuery query = SearchQuery.builder("apple")
     *       .frequency(100)
     *       .userId("user-42")
     *       .build();
     */
    public static Builder builder(String queryText) {
        return new Builder(queryText);
    }

    public static class Builder {
        // Required
        private final String queryText;

        // Optional with defaults
        private long frequency = 1;
        private LocalDateTime lastSearched = LocalDateTime.now();
        private String userId = null; // nullable — anonymous search

        private Builder(String queryText) {
            if (queryText == null || queryText.isBlank()) {
                throw new IllegalArgumentException("queryText cannot be null or blank");
            }
            this.queryText = queryText.toLowerCase().trim();
        }

        public Builder frequency(long frequency) {
            if (frequency < 0) {
                throw new IllegalArgumentException("frequency cannot be negative");
            }
            this.frequency = frequency;
            return this;
        }

        public Builder lastSearched(LocalDateTime lastSearched) {
            this.lastSearched = lastSearched;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public SearchQuery build() {
            return new SearchQuery(this);
        }
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "SearchQuery{" +
                "text='" + queryText + '\'' +
                ", freq=" + frequency +
                ", lastSearched=" + lastSearched +
                ", userId='" + userId + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchQuery that = (SearchQuery) o;
        return queryText.equals(that.queryText);
    }

    @Override
    public int hashCode() {
        return queryText.hashCode();
    }
}
