package com.systemdesign.gateway.model;

/**
 * Result of a rate-limit check against a token bucket or sliding window limiter.
 *
 * Flow: HttpRequest → RateLimiter.check() → RateLimitResult → allow or reject with 429
 */
public class RateLimitResult {

    private final boolean allowed;          // whether the request is permitted
    private final int remainingTokens;      // tokens left in the current window
    private final long retryAfterMs;        // milliseconds until the client can retry (0 if allowed)

    private RateLimitResult(boolean allowed, int remainingTokens, long retryAfterMs) {
        this.allowed = allowed;
        this.remainingTokens = remainingTokens;
        this.retryAfterMs = retryAfterMs;
    }

    // ── Getters ──

    public boolean isAllowed() { return allowed; }
    public int getRemainingTokens() { return remainingTokens; }
    public long getRetryAfterMs() { return retryAfterMs; }

    // ── Static factories ──

    /** Request is allowed; remaining indicates how many tokens are left. */
    public static RateLimitResult allowed(int remaining) {
        return new RateLimitResult(true, remaining, 0);
    }

    /** Request is denied; retryAfterMs tells the client when to retry. */
    public static RateLimitResult denied(long retryAfterMs) {
        return new RateLimitResult(false, 0, retryAfterMs);
    }

    @Override
    public String toString() {
        return "RateLimitResult{allowed=%s, remaining=%d, retryAfterMs=%d}".formatted(
                allowed, remainingTokens, retryAfterMs);
    }
}
