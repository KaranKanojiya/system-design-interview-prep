package com.systemdesign.ratelimiter.strategy;

import com.systemdesign.ratelimiter.model.Algorithm;
import com.systemdesign.ratelimiter.model.RateLimitResult;
import com.systemdesign.ratelimiter.model.RateLimitRule;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leaky Bucket algorithm: requests enter a queue (bucket) and "leak" out at a fixed rate.
 * New requests are rejected when the bucket is full.
 *
 * Pros: Guarantees a uniform outflow rate, smooths bursts.
 * Cons: Recent requests may be delayed; bursty traffic gets queued or dropped.
 *
 * Used by: Network traffic shaping, Shopify.
 */
public class LeakyBucketStrategy implements RateLimiterStrategy {

    private final Map<String, LeakyBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        LeakyBucket bucket = buckets.computeIfAbsent(key,
                k -> new LeakyBucket(rule.getMaxRequests(), rule.getWindowSizeMs()));

        synchronized (bucket) {
            long now = System.currentTimeMillis();
            bucket.leak(now);

            if (bucket.queue.size() < bucket.capacity) {
                bucket.queue.addLast(now);
                int remaining = bucket.capacity - bucket.queue.size();
                long resetAtMs = now + (long) (bucket.queue.size() / bucket.leakRatePerMs);
                return RateLimitResult.allowed(remaining, rule.getMaxRequests(), resetAtMs);
            } else {
                // Bucket full — calculate when next slot opens (next leak)
                long retryAfterMs = (long) (1.0 / bucket.leakRatePerMs);
                long resetAtMs = now + retryAfterMs;
                return RateLimitResult.rejected(rule.getMaxRequests(), retryAfterMs, resetAtMs);
            }
        }
    }

    @Override
    public void reset(String key) {
        buckets.remove(key);
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.LEAKY_BUCKET;
    }

    /** Internal state: a queue of request timestamps that "leak" at a fixed rate. */
    private static class LeakyBucket {
        final Deque<Long> queue = new ArrayDeque<>();
        final int capacity;
        final double leakRatePerMs;
        long lastLeakTimeMs;

        LeakyBucket(int capacity, long windowSizeMs) {
            this.capacity = capacity;
            this.leakRatePerMs = (double) capacity / windowSizeMs;
            this.lastLeakTimeMs = System.currentTimeMillis();
        }

        void leak(long now) {
            long elapsed = now - lastLeakTimeMs;
            if (elapsed > 0) {
                int leaked = (int) (elapsed * leakRatePerMs);
                for (int i = 0; i < leaked && !queue.isEmpty(); i++) {
                    queue.pollFirst();
                }
                lastLeakTimeMs = now;
            }
        }
    }
}
