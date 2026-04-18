package com.systemdesign.ratelimiter.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable result of a rate limit check.
 * Provides HTTP-standard rate limit headers for API responses.
 */
public class RateLimitResult {

    private final boolean allowed;
    private final int remaining;
    private final long retryAfterMs;
    private final int limit;
    private final long resetAtMs;

    private RateLimitResult(boolean allowed, int remaining, long retryAfterMs, int limit, long resetAtMs) {
        this.allowed = allowed;
        this.remaining = remaining;
        this.retryAfterMs = retryAfterMs;
        this.limit = limit;
        this.resetAtMs = resetAtMs;
    }

    // --- Static factory methods ---

    public static RateLimitResult allowed(int remaining, int limit, long resetAtMs) {
        return new RateLimitResult(true, remaining, 0, limit, resetAtMs);
    }

    public static RateLimitResult rejected(int limit, long retryAfterMs, long resetAtMs) {
        return new RateLimitResult(false, 0, retryAfterMs, limit, resetAtMs);
    }

    // --- Getters ---

    public boolean isAllowed() { return allowed; }
    public int getRemaining() { return remaining; }
    public long getRetryAfterMs() { return retryAfterMs; }
    public int getLimit() { return limit; }
    public long getResetAtMs() { return resetAtMs; }

    /**
     * Returns standard HTTP rate limit headers.
     * Follows RFC 6585 and draft-ietf-httpapi-ratelimit-headers conventions.
     */
    public Map<String, String> getHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-RateLimit-Limit", String.valueOf(limit));
        headers.put("X-RateLimit-Remaining", String.valueOf(remaining));
        headers.put("X-RateLimit-Reset", String.valueOf(resetAtMs));
        if (!allowed) {
            headers.put("Retry-After", String.valueOf(retryAfterMs));
        }
        return headers;
    }

    @Override
    public String toString() {
        if (allowed) {
            return "ALLOWED [remaining=%d, limit=%d, resetAt=%d]".formatted(remaining, limit, resetAtMs);
        }
        return "REJECTED [limit=%d, retryAfter=%dms, resetAt=%d]".formatted(limit, retryAfterMs, resetAtMs);
    }
}
