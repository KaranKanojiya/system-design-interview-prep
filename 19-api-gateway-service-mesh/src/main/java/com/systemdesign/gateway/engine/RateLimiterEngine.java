package com.systemdesign.gateway.engine;

// Wiring: RateLimiterEngine enforces per-key token bucket rate limiting.
// Used by GatewayService -> before routing -> checks if client/API key has available tokens.
// Token bucket: tokens refill at a constant rate, each request consumes one token.

import com.systemdesign.gateway.model.RateLimitResult;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket rate limiter. Each key (client IP, API key, etc.) gets an
 * independent bucket that refills at a configured rate.
 */
public class RateLimiterEngine {

    // Per-key token buckets
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /** Configures (or reconfigures) a token bucket for the given key. */
    public void configure(String key, int maxTokens, double refillRate) {
        buckets.put(key, new TokenBucket(maxTokens, refillRate));
        System.out.println("[RATE LIMITER] Configured bucket: key=" + key
                + " maxTokens=" + maxTokens + " refillRate=" + refillRate + "/sec");
    }

    /**
     * Attempts to consume one token from the bucket for the given key.
     * Refills tokens based on elapsed time, then tries to consume.
     * Returns a RateLimitResult indicating allowed/denied with remaining tokens.
     */
    public RateLimitResult tryConsume(String key) {
        TokenBucket bucket = buckets.get(key);
        if (bucket == null) {
            // No bucket configured — allow by default (no limit)
            return RateLimitResult.allowed(-1);
        }

        synchronized (bucket) {
            bucket.refill();

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return RateLimitResult.allowed((int) bucket.tokens);
            } else {
                // Estimate retry-after based on refill rate
                long retryAfterMs = (long) (1000.0 / bucket.refillRate);
                return RateLimitResult.denied(retryAfterMs);
            }
        }
    }

    /** Returns the number of remaining tokens for a key, or -1 if not configured. */
    public int getRemaining(String key) {
        TokenBucket bucket = buckets.get(key);
        if (bucket == null) {
            return -1;
        }
        synchronized (bucket) {
            bucket.refill();
            return (int) bucket.tokens;
        }
    }

    /** Resets a bucket to full capacity. */
    public void reset(String key) {
        TokenBucket bucket = buckets.get(key);
        if (bucket != null) {
            synchronized (bucket) {
                bucket.tokens = bucket.maxTokens;
                bucket.lastRefillTime = Instant.now();
            }
        }
    }

    // ── Inner class: TokenBucket ──

    /**
     * A single token bucket with configurable capacity and refill rate.
     */
    private static class TokenBucket {
        double tokens;            // current available tokens
        final int maxTokens;     // maximum bucket capacity
        final double refillRate; // tokens added per second
        Instant lastRefillTime;  // last time tokens were refilled

        TokenBucket(int maxTokens, double refillRate) {
            this.maxTokens = maxTokens;
            this.tokens = maxTokens; // start full
            this.refillRate = refillRate;
            this.lastRefillTime = Instant.now();
        }

        /** Refills tokens based on elapsed time since last refill. */
        void refill() {
            Instant now = Instant.now();
            double elapsedSeconds = (now.toEpochMilli() - lastRefillTime.toEpochMilli()) / 1000.0;
            double tokensToAdd = elapsedSeconds * refillRate;

            if (tokensToAdd > 0) {
                tokens = Math.min(maxTokens, tokens + tokensToAdd);
                lastRefillTime = now;
            }
        }
    }
}
