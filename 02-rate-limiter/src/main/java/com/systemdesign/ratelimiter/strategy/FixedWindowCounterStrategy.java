package com.systemdesign.ratelimiter.strategy;

import com.systemdesign.ratelimiter.model.Algorithm;
import com.systemdesign.ratelimiter.model.RateLimitResult;
import com.systemdesign.ratelimiter.model.RateLimitRule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed Window Counter: divides time into fixed windows and counts requests per window.
 *
 * Pros: Simple, low memory (one counter per key).
 * Cons: Boundary burst problem — a user can send 2x the limit by clustering
 *       requests at the end of one window and the start of the next.
 *
 * Example: 10 req/min limit. User sends 10 at 0:59, 10 more at 1:00 = 20 in 2 seconds.
 */
public class FixedWindowCounterStrategy implements RateLimiterStrategy {

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        WindowCounter counter = counters.computeIfAbsent(key, k -> new WindowCounter(rule.getWindowSizeMs()));

        synchronized (counter) {
            long now = System.currentTimeMillis();
            long currentWindowStart = (now / rule.getWindowSizeMs()) * rule.getWindowSizeMs();

            // Window has advanced — reset the counter
            if (counter.windowStartMs != currentWindowStart) {
                counter.count.set(0);
                counter.windowStartMs = currentWindowStart;
            }

            int currentCount = counter.count.incrementAndGet();
            long resetAtMs = currentWindowStart + rule.getWindowSizeMs();

            if (currentCount <= rule.getMaxRequests()) {
                int remaining = rule.getMaxRequests() - currentCount;
                return RateLimitResult.allowed(remaining, rule.getMaxRequests(), resetAtMs);
            } else {
                // NOTE: Boundary problem — user can send 2x requests at window boundary
                long retryAfterMs = resetAtMs - now;
                return RateLimitResult.rejected(rule.getMaxRequests(), retryAfterMs, resetAtMs);
            }
        }
    }

    @Override
    public void reset(String key) {
        counters.remove(key);
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.FIXED_WINDOW;
    }

    /** Per-key state: a request count and the start of the current window. */
    private static class WindowCounter {
        final AtomicInteger count = new AtomicInteger(0);
        long windowStartMs;

        WindowCounter(long windowSizeMs) {
            this.windowStartMs = (System.currentTimeMillis() / windowSizeMs) * windowSizeMs;
        }
    }
}
