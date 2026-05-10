package com.systemdesign.scheduler.strategy.retry;

import com.systemdesign.scheduler.model.Task;

// Wiring: Returns a constant delay between retries regardless of attempt number.
// Simpler than exponential backoff, suitable for transient errors where
// the recovery time is predictable (e.g., database connection drops).
public class FixedIntervalRetryStrategy implements RetryStrategy {

    private final long fixedDelayMs;

    public FixedIntervalRetryStrategy(long fixedDelayMs) {
        if (fixedDelayMs <= 0) {
            throw new IllegalArgumentException("fixedDelayMs must be positive");
        }
        this.fixedDelayMs = fixedDelayMs;
    }

    @Override
    public boolean shouldRetry(Task task, int attemptNumber, String errorMessage) {
        return attemptNumber <= task.getMaxRetries();
    }

    @Override
    public long getRetryDelayMillis(int attemptNumber) {
        return fixedDelayMs;
    }

    @Override
    public String getStrategyName() {
        return "FIXED_INTERVAL";
    }
}
