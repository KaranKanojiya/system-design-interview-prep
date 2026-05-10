package com.systemdesign.observability.strategy.sampling;

import com.systemdesign.observability.model.TraceContext;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// Wiring: Rate-limited sampling caps the number of traces collected per second.
// Thread-safe via AtomicInteger (counter) and AtomicLong (window start).
// The window resets every second: if the current epoch-second differs from windowStart,
// we reset the counter and start counting again. This prevents bursty traffic from
// overwhelming the collector while guaranteeing a minimum level of observability.

/**
 * Samples up to N traces per second using a sliding-window counter.
 * Thread-safe — safe for concurrent use by multiple request-handling threads.
 */
public class RateLimitedSamplingStrategy implements SamplingStrategy {

    private final int maxPerSecond;
    private final AtomicInteger counter;
    private final AtomicLong windowStart;  // epoch second of the current window

    /**
     * @param maxPerSecond maximum number of traces to sample per second
     */
    public RateLimitedSamplingStrategy(int maxPerSecond) {
        if (maxPerSecond <= 0) {
            throw new IllegalArgumentException("maxPerSecond must be positive, got: " + maxPerSecond);
        }
        this.maxPerSecond = maxPerSecond;
        this.counter = new AtomicInteger(0);
        this.windowStart = new AtomicLong(currentSecond());
    }

    /**
     * Decides whether to sample based on the per-second rate limit.
     *
     * 1. Read the current epoch second
     * 2. If it differs from windowStart → new window; reset counter to 0, update windowStart
     * 3. If counter < maxPerSecond → increment counter and return true (sampled)
     * 4. Otherwise → return false (rate limit exceeded)
     *
     * Note: there is a benign race between step 2 and step 3 — two threads may both
     * see a new second and both reset. This is acceptable because it can only result in
     * slightly more samples (never fewer), and exactness is not required for sampling.
     */
    @Override
    public boolean shouldSample(TraceContext context) {
        long now = currentSecond();
        long window = windowStart.get();

        if (now != window) {
            // New second window — reset the counter
            if (windowStart.compareAndSet(window, now)) {
                counter.set(0);
            }
        }

        // Try to claim a slot within the current window
        if (counter.incrementAndGet() <= maxPerSecond) {
            return true;
        }

        // Rate limit exceeded for this second
        return false;
    }

    /** Delegates to the context-only overload — rate limiting is operation-agnostic. */
    @Override
    public boolean shouldSample(TraceContext context, String operationName) {
        return shouldSample(context);
    }

    @Override
    public String getStrategyName() {
        return "RATE_LIMITED";
    }

    /** Returns the current epoch second. Extracted for testability. */
    private long currentSecond() {
        return System.currentTimeMillis() / 1000;
    }
}
