package com.systemdesign.ratelimiter.strategy;

import com.systemdesign.ratelimiter.model.Algorithm;
import com.systemdesign.ratelimiter.model.RateLimitResult;
import com.systemdesign.ratelimiter.model.RateLimitRule;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Token Bucket algorithm: tokens accumulate at a steady rate up to a max capacity.
 * Each request consumes one token. Allows controlled bursts.
 *
 * Pros: Smooth rate limiting, allows bursts, simple to understand.
 * Cons: Requires per-key state (token count + last refill time).
 *
 * Used by: AWS API Gateway, Stripe, many production systems.
 */
public class TokenBucketStrategy implements RateLimiterStrategy {

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        TokenBucket bucket = buckets.computeIfAbsent(key,
                k -> new TokenBucket(rule.getBurstCapacity(), rule.getMaxRequests(), rule.getWindowSizeMs()));

        // Synchronized per-bucket (fine-grained locking, not global)
        synchronized (bucket) {
            long now = System.currentTimeMillis();
            bucket.refill(now);

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                int remaining = (int) bucket.tokens;
                long resetAtMs = now + (long) ((bucket.capacity - bucket.tokens) / bucket.refillRatePerMs);
                return RateLimitResult.allowed(remaining, rule.getMaxRequests(), resetAtMs);
            } else {
                // Not enough tokens — calculate when one token will be available
                long retryAfterMs = (long) ((1.0 - bucket.tokens) / bucket.refillRatePerMs);
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
        return Algorithm.TOKEN_BUCKET;
    }

    /** Internal state for one key's token bucket. */
    private static class TokenBucket {
        double tokens;
        long lastRefillTimeMs;
        final int capacity;
        final double refillRatePerMs;

        TokenBucket(int capacity, int maxRequests, long windowSizeMs) {
            this.capacity = capacity;
            this.tokens = capacity; // start full
            this.lastRefillTimeMs = System.currentTimeMillis();
            this.refillRatePerMs = (double) maxRequests / windowSizeMs;
        }

        void refill(long now) {
            long elapsed = now - lastRefillTimeMs;
            if (elapsed > 0) {
                double newTokens = elapsed * refillRatePerMs;
                tokens = Math.min(capacity, tokens + newTokens);
                lastRefillTimeMs = now;
            }
        }
    }
}
