package com.systemdesign.ratelimiter.strategy;

import com.systemdesign.ratelimiter.model.Algorithm;
import com.systemdesign.ratelimiter.model.RateLimitResult;
import com.systemdesign.ratelimiter.model.RateLimitRule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sliding Window Log: maintains a log of exact timestamps for each request.
 * Evicts entries older than the window, then checks if count < limit.
 *
 * Most accurate algorithm — no boundary burst problem.
 * Cons: O(n) memory per key where n = maxRequests. Not ideal for high-volume keys.
 *
 * Used when: precision matters more than memory (e.g., billing, auth endpoints).
 */
public class SlidingWindowLogStrategy implements RateLimiterStrategy {

    private final Map<String, ConcurrentLinkedDeque<Long>> requestLogs = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        ConcurrentLinkedDeque<Long> log = requestLogs.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        synchronized (log) {
            long now = System.currentTimeMillis();
            long windowStart = now - rule.getWindowSizeMs();

            // Evict timestamps outside the sliding window
            while (!log.isEmpty() && log.peekFirst() <= windowStart) {
                log.pollFirst();
            }

            if (log.size() < rule.getMaxRequests()) {
                log.addLast(now);
                int remaining = rule.getMaxRequests() - log.size();
                long resetAtMs = log.peekFirst() + rule.getWindowSizeMs();
                return RateLimitResult.allowed(remaining, rule.getMaxRequests(), resetAtMs);
            } else {
                // Denied — retry after the oldest entry expires from the window
                long oldestTimestamp = log.peekFirst();
                long retryAfterMs = oldestTimestamp + rule.getWindowSizeMs() - now;
                long resetAtMs = oldestTimestamp + rule.getWindowSizeMs();
                return RateLimitResult.rejected(rule.getMaxRequests(), Math.max(retryAfterMs, 1), resetAtMs);
            }
        }
    }

    @Override
    public void reset(String key) {
        requestLogs.remove(key);
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.SLIDING_WINDOW_LOG;
    }
}
