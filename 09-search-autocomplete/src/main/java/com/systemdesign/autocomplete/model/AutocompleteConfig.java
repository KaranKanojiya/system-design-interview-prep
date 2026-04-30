package com.systemdesign.autocomplete.model;

/**
 * AutocompleteConfig — Centralized configuration for the autocomplete system.
 *
 * WHY Builder pattern here?
 * -------------------------
 * All fields have sensible defaults, but must be overridable for:
 *   - Testing (small cache, low maxResults)
 *   - Production tuning (large cache, aggressive decay)
 *   - A/B testing (different topK values)
 *
 * Ugly approach:
 *   // Magic numbers scattered across 10 classes
 *   int maxResults = 10; // in AutocompleteService
 *   int cacheSize = 10000; // in InMemorySuggestionCache
 *   double decay = 0.01; // in TimeDecayRankingStrategy
 *   // Want to change maxResults? Grep across entire codebase. Miss one? Bug.
 *
 * Clean approach:
 *   // Single source of truth, injected everywhere
 *   AutocompleteConfig config = AutocompleteConfig.builder().maxResults(5).build();
 *   // Every component reads from this config object
 *
 * Wiring:
 *   AppConfig creates AutocompleteConfig → passes to:
 *     - AutocompleteService (maxResults, maxPrefixLength)
 *     - TopKTrie (topKPerNode)
 *     - InMemorySuggestionCache (cacheSize, cacheTtlSeconds)
 *     - TimeDecayRankingStrategy (decayFactor)
 *     - DataCollectionService (minFrequency)
 */
public class AutocompleteConfig {

    // -----------------------------------------------------------------------
    // Fields with production-ready defaults
    // -----------------------------------------------------------------------

    /** Maximum number of suggestions to return per request. */
    private final int maxResults;

    /** Maximum prefix length to process. Longer prefixes are truncated. Prevents abuse. */
    private final int maxPrefixLength;

    /** Minimum frequency for a query to be eligible as a suggestion. Filters noise. */
    private final long minFrequency;

    /**
     * Time-decay factor (lambda) for the formula: score = freq * exp(-lambda * hoursAge).
     * 0.01 ≈ half-life of ~70 hours (score halves every ~3 days).
     * Higher = more aggressive decay (recent queries dominate).
     * Lower = more stable (historical popularity dominates).
     */
    private final double decayFactor;

    /** Number of top suggestions pre-computed at each TrieNode (TopKTrie optimization). */
    private final int topKPerNode;

    /** Maximum number of entries in the suggestion cache. */
    private final int cacheSize;

    /** Time-to-live for cache entries in seconds. Entries older than this are stale. */
    private final int cacheTtlSeconds;

    // -----------------------------------------------------------------------
    // Private constructor — only Builder can create instances
    // -----------------------------------------------------------------------

    private AutocompleteConfig(Builder builder) {
        this.maxResults = builder.maxResults;
        this.maxPrefixLength = builder.maxPrefixLength;
        this.minFrequency = builder.minFrequency;
        this.decayFactor = builder.decayFactor;
        this.topKPerNode = builder.topKPerNode;
        this.cacheSize = builder.cacheSize;
        this.cacheTtlSeconds = builder.cacheTtlSeconds;
    }

    // -----------------------------------------------------------------------
    // Getters
    // -----------------------------------------------------------------------

    public int getMaxResults() {
        return maxResults;
    }

    public int getMaxPrefixLength() {
        return maxPrefixLength;
    }

    public long getMinFrequency() {
        return minFrequency;
    }

    public double getDecayFactor() {
        return decayFactor;
    }

    public int getTopKPerNode() {
        return topKPerNode;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a default config. Equivalent to builder().build().
     */
    public static AutocompleteConfig defaultConfig() {
        return new Builder().build();
    }

    public static class Builder {
        private int maxResults = 10;
        private int maxPrefixLength = 50;
        private long minFrequency = 1;
        private double decayFactor = 0.01;
        private int topKPerNode = 10;
        private int cacheSize = 10000;
        private int cacheTtlSeconds = 300; // 5 minutes

        public Builder maxResults(int maxResults) {
            if (maxResults <= 0) throw new IllegalArgumentException("maxResults must be > 0");
            this.maxResults = maxResults;
            return this;
        }

        public Builder maxPrefixLength(int maxPrefixLength) {
            if (maxPrefixLength <= 0) throw new IllegalArgumentException("maxPrefixLength must be > 0");
            this.maxPrefixLength = maxPrefixLength;
            return this;
        }

        public Builder minFrequency(long minFrequency) {
            if (minFrequency < 0) throw new IllegalArgumentException("minFrequency must be >= 0");
            this.minFrequency = minFrequency;
            return this;
        }

        public Builder decayFactor(double decayFactor) {
            if (decayFactor < 0) throw new IllegalArgumentException("decayFactor must be >= 0");
            this.decayFactor = decayFactor;
            return this;
        }

        public Builder topKPerNode(int topKPerNode) {
            if (topKPerNode <= 0) throw new IllegalArgumentException("topKPerNode must be > 0");
            this.topKPerNode = topKPerNode;
            return this;
        }

        public Builder cacheSize(int cacheSize) {
            if (cacheSize <= 0) throw new IllegalArgumentException("cacheSize must be > 0");
            this.cacheSize = cacheSize;
            return this;
        }

        public Builder cacheTtlSeconds(int cacheTtlSeconds) {
            if (cacheTtlSeconds <= 0) throw new IllegalArgumentException("cacheTtlSeconds must be > 0");
            this.cacheTtlSeconds = cacheTtlSeconds;
            return this;
        }

        public AutocompleteConfig build() {
            return new AutocompleteConfig(this);
        }
    }

    @Override
    public String toString() {
        return "AutocompleteConfig{" +
                "maxResults=" + maxResults +
                ", maxPrefixLength=" + maxPrefixLength +
                ", minFrequency=" + minFrequency +
                ", decayFactor=" + decayFactor +
                ", topKPerNode=" + topKPerNode +
                ", cacheSize=" + cacheSize +
                ", cacheTtlSeconds=" + cacheTtlSeconds +
                '}';
    }
}
