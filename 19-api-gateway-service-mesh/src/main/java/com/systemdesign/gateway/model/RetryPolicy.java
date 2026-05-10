package com.systemdesign.gateway.model;

import java.util.Collections;
import java.util.Set;

/**
 * Retry configuration with exponential backoff for upstream service calls.
 *
 * Flow: Request fails → RetryPolicy.shouldRetry() → yes → wait getDelay(attempt) → retry
 */
public class RetryPolicy {

    private final int maxRetries;                   // maximum number of retry attempts
    private final long initialDelayMs;              // base delay before first retry
    private final long maxDelayMs;                  // cap on exponential backoff
    private final Set<Integer> retryableStatusCodes; // HTTP status codes eligible for retry

    public RetryPolicy(int maxRetries, long initialDelayMs, long maxDelayMs, Set<Integer> retryableStatusCodes) {
        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.retryableStatusCodes = Collections.unmodifiableSet(retryableStatusCodes);
    }

    // ── Getters ──

    public int getMaxRetries() { return maxRetries; }
    public long getInitialDelayMs() { return initialDelayMs; }
    public long getMaxDelayMs() { return maxDelayMs; }
    public Set<Integer> getRetryableStatusCodes() { return retryableStatusCodes; }

    // ── Methods ──

    /**
     * Returns true if the request should be retried based on the status code and current attempt.
     *
     * @param statusCode HTTP status code from the failed response
     * @param attempt    current attempt number (1-based)
     */
    public boolean shouldRetry(int statusCode, int attempt) {
        return attempt <= maxRetries && retryableStatusCodes.contains(statusCode);
    }

    /**
     * Calculates the delay before the given retry attempt using exponential backoff.
     *
     * Formula: min(initialDelayMs * 2^(attempt-1), maxDelayMs)
     *
     * @param attempt retry attempt number (1-based)
     * @return delay in milliseconds
     */
    public long getDelay(int attempt) {
        long delay = initialDelayMs * (1L << (attempt - 1)); // exponential: 100, 200, 400, ...
        return Math.min(delay, maxDelayMs);
    }

    // ── Static factory ──

    /** Default retry policy: 3 retries, 100ms initial delay, 5s max, retries on 502/503/504. */
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3, 100, 5000, Set.of(502, 503, 504));
    }

    @Override
    public String toString() {
        return "RetryPolicy{maxRetries=%d, initialDelayMs=%d, maxDelayMs=%d, codes=%s}".formatted(
                maxRetries, initialDelayMs, maxDelayMs, retryableStatusCodes);
    }
}
