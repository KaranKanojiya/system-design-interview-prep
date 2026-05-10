package com.systemdesign.scheduler.strategy.retry;

import com.systemdesign.scheduler.model.Task;

// Strategy Pattern (GoF) — determines retry behavior after task failure
public interface RetryStrategy {

    boolean shouldRetry(Task task, int attemptNumber, String errorMessage);

    long getRetryDelayMillis(int attemptNumber);

    String getStrategyName();
}
