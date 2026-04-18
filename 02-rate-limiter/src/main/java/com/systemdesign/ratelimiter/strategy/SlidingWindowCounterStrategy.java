package com.systemdesign.ratelimiter.strategy;

import com.systemdesign.ratelimiter.model.Algorithm;
import com.systemdesign.ratelimiter.model.RateLimitResult;
import com.systemdesign.ratelimiter.model.RateLimitRule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding Window Counter: approximates a sliding window using weighted counts
 * from the current and previous fixed windows.
 *
 * Best balance of accuracy and memory — uses only two counters per key.
 * The weighted formula: count = prevCount * (1 - overlapFraction) + currentCount
 *
 * Accuracy is within ~0.003% of true sliding window (Cloudflare's analysis).
 * Used by: Cloudflare, Redis rate limiting modules.
 */
public class SlidingWindowCounterStrategy implements RateLimiterStrategy {

    private final Map<String, SlidingWindow> windows = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        SlidingWindow window = windows.computeIfAbsent(key, k -> new SlidingWindow(rule.getWindowSizeMs()));

        synchronized (window) {
            long now = System.currentTimeMillis();
            long currentWindowStart = (now / rule.getWindowSizeMs()) * rule.getWindowSizeMs();

            // Advance window if needed
            if (currentWindowStart != window.currentWindowStart) {
                if (currentWindowStart - window.currentWindowStart >= 2 * rule.getWindowSizeMs()) {
                    // More than two windows have passed — both counts are stale
                    window.previousCount = 0;
                } else {
                    window.previousCount = window.currentCount;
                }
                window.currentCount = 0;
                window.currentWindowStart = currentWindowStart;
            }

            // Weighted count: blend previous window's count with current
            double overlapFraction = (double) (now - currentWindowStart) / rule.getWindowSizeMs();
            double weightedCount = window.previousCount * (1.0 - overlapFraction) + window.currentCount;
            long resetAtMs = currentWindowStart + rule.getWindowSizeMs();

            if (weightedCount < rule.getMaxRequests()) {
                window.currentCount++;
                int remaining = (int) (rule.getMaxRequests() - Math.ceil(weightedCount + 1));
                remaining = Math.max(remaining, 0);
                return RateLimitResult.allowed(remaining, rule.getMaxRequests(), resetAtMs);
            } else {
                long retryAfterMs = resetAtMs - now;
                return RateLimitResult.rejected(rule.getMaxRequests(), Math.max(retryAfterMs, 1), resetAtMs);
            }
        }
    }

    @Override
    public void reset(String key) {
        windows.remove(key);
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.SLIDING_WINDOW_COUNTER;
    }

    /** Two-counter state for the sliding window approximation. */
    private static class SlidingWindow {
        int previousCount;
        int currentCount;
        long currentWindowStart;
        final long windowSizeMs;

        SlidingWindow(long windowSizeMs) {
            this.windowSizeMs = windowSizeMs;
            this.currentWindowStart = (System.currentTimeMillis() / windowSizeMs) * windowSizeMs;
            this.previousCount = 0;
            this.currentCount = 0;
        }
    }
}
