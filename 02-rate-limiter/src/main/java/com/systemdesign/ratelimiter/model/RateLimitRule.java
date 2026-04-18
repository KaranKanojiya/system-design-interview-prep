package com.systemdesign.ratelimiter.model;

/**
 * Defines a rate limiting rule — how many requests, over what window, using which algorithm.
 * Uses the Builder pattern for readable construction with sensible defaults.
 */
public class RateLimitRule {

    private final String id;
    private final String key;
    private final int maxRequests;
    private final long windowSizeMs;
    private final Algorithm algorithm;
    private final int burstCapacity;
    private final boolean enabled;

    private RateLimitRule(Builder builder) {
        this.id = builder.id;
        this.key = builder.key;
        this.maxRequests = builder.maxRequests;
        this.windowSizeMs = builder.windowSizeMs;
        this.algorithm = builder.algorithm;
        this.burstCapacity = builder.burstCapacity == -1 ? builder.maxRequests : builder.burstCapacity;
        this.enabled = builder.enabled;
    }

    // --- Getters ---

    public String getId() { return id; }
    public String getKey() { return key; }
    public int getMaxRequests() { return maxRequests; }
    public long getWindowSizeMs() { return windowSizeMs; }
    public Algorithm getAlgorithm() { return algorithm; }
    public int getBurstCapacity() { return burstCapacity; }
    public boolean isEnabled() { return enabled; }

    @Override
    public String toString() {
        return "RateLimitRule{key='%s', maxRequests=%d, windowMs=%d, algorithm=%s, burst=%d, enabled=%s}"
                .formatted(key, maxRequests, windowSizeMs, algorithm.name(), burstCapacity, enabled);
    }

    // --- Builder ---

    public static Builder builder(String key, int maxRequests, long windowSizeMs) {
        return new Builder(key, maxRequests, windowSizeMs);
    }

    public static class Builder {
        // Required
        private final String key;
        private final int maxRequests;
        private final long windowSizeMs;

        // Optional with defaults
        private String id;
        private Algorithm algorithm = Algorithm.TOKEN_BUCKET;
        private int burstCapacity = -1; // sentinel: defaults to maxRequests
        private boolean enabled = true;

        private Builder(String key, int maxRequests, long windowSizeMs) {
            this.key = key;
            this.maxRequests = maxRequests;
            this.windowSizeMs = windowSizeMs;
            this.id = key; // default id = key
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder algorithm(Algorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public Builder burstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public RateLimitRule build() {
            if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive");
            if (windowSizeMs <= 0) throw new IllegalArgumentException("windowSizeMs must be positive");
            return new RateLimitRule(this);
        }
    }
}
