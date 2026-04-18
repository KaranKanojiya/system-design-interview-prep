package com.systemdesign.ratelimiter.model;

/**
 * Supported rate limiting algorithms.
 * Each algorithm offers different trade-offs between accuracy, memory, and complexity.
 */
public enum Algorithm {

    TOKEN_BUCKET("Allows bursts up to bucket capacity, refills at steady rate"),
    LEAKY_BUCKET("Processes requests at a fixed rate, smoothing out bursts"),
    FIXED_WINDOW("Counts requests in fixed time windows — simple but has boundary burst problem"),
    SLIDING_WINDOW_LOG("Tracks exact timestamps per request — most accurate, highest memory"),
    SLIDING_WINDOW_COUNTER("Weighted approximation across two windows — best accuracy/memory balance");

    private final String description;

    Algorithm(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return name() + " — " + description;
    }
}
