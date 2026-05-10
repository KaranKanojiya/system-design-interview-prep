package com.systemdesign.scheduler.model;

// Wiring: Configures retry behavior for failed tasks.
// Consumed by RetryHandler to decide whether to reschedule a task
// and how long to wait before the next attempt.
public class RetryPolicy {

    private final int maxRetries;
    private final long initialDelayMillis;
    private final long maxDelayMillis;
    private final double backoffMultiplier;

    private RetryPolicy(int maxRetries, long initialDelayMillis, long maxDelayMillis, double backoffMultiplier) {
        this.maxRetries = maxRetries;
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.backoffMultiplier = backoffMultiplier;
    }

    // --- Static factory methods ---

    public static RetryPolicy noRetry() {
        return new RetryPolicy(0, 0, 0, 1.0);
    }

    public static RetryPolicy fixedInterval(int maxRetries, long delayMs) {
        return new RetryPolicy(maxRetries, delayMs, delayMs, 1.0);
    }

    public static RetryPolicy exponentialBackoff(int maxRetries, long initialDelayMs, double multiplier) {
        long maxDelay = (long) (initialDelayMs * Math.pow(multiplier, maxRetries));
        return new RetryPolicy(maxRetries, initialDelayMs, maxDelay, multiplier);
    }

    /**
     * Computes the delay in milliseconds before the given retry attempt.
     * Attempt numbering starts at 1 (first retry).
     */
    public long getDelayForAttempt(int attempt) {
        if (attempt <= 0) {
            return 0;
        }
        long delay = (long) (initialDelayMillis * Math.pow(backoffMultiplier, attempt - 1));
        return Math.min(delay, maxDelayMillis);
    }

    public boolean shouldRetry(int currentAttempt) {
        return currentAttempt < maxRetries;
    }

    // --- Getters ---

    public int getMaxRetries() { return maxRetries; }
    public long getInitialDelayMillis() { return initialDelayMillis; }
    public long getMaxDelayMillis() { return maxDelayMillis; }
    public double getBackoffMultiplier() { return backoffMultiplier; }

    @Override
    public String toString() {
        return "RetryPolicy{maxRetries=" + maxRetries
                + ", initialDelay=" + initialDelayMillis + "ms"
                + ", maxDelay=" + maxDelayMillis + "ms"
                + ", multiplier=" + backoffMultiplier + "}";
    }
}
