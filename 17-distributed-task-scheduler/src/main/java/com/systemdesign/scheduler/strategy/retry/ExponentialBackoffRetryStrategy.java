package com.systemdesign.scheduler.strategy.retry;

import com.systemdesign.scheduler.model.Task;

import java.util.concurrent.ThreadLocalRandom;

// Wiring: Computes retry delay as min(initialDelay * multiplier^(attempt-1), maxDelay)
// with ±10% jitter to prevent thundering herd when many tasks fail simultaneously.
// Used by TaskExecutor to space out retry attempts with increasing backoff.
public class ExponentialBackoffRetryStrategy implements RetryStrategy {

    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double multiplier;

    public ExponentialBackoffRetryStrategy(long initialDelayMs, long maxDelayMs, double multiplier) {
        if (initialDelayMs <= 0) {
            throw new IllegalArgumentException("initialDelayMs must be positive");
        }
        if (maxDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("maxDelayMs must be >= initialDelayMs");
        }
        if (multiplier <= 1.0) {
            throw new IllegalArgumentException("multiplier must be > 1.0");
        }
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.multiplier = multiplier;
    }

    @Override
    public boolean shouldRetry(Task task, int attemptNumber, String errorMessage) {
        return attemptNumber <= task.getMaxRetries();
    }

    @Override
    public long getRetryDelayMillis(int attemptNumber) {
        // 1. Compute base delay: initialDelay * multiplier^(attempt-1)
        double baseDelay = initialDelayMs * Math.pow(multiplier, attemptNumber - 1);

        // 2. Cap at maxDelay
        long cappedDelay = (long) Math.min(baseDelay, maxDelayMs);

        // 3. Apply ±10% jitter to prevent thundering herd
        double jitterFactor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
        return (long) (cappedDelay * jitterFactor);
    }

    @Override
    public String getStrategyName() {
        return "EXPONENTIAL_BACKOFF";
    }
}
