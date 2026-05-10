package com.systemdesign.gateway.exception;

/**
 * Thrown when a client exceeds their configured rate limit.
 *
 * Flow: HttpRequest → RateLimiterEngine.tryAcquire() → denied → RateLimitExceededException → 429
 */
public class RateLimitExceededException extends GatewayException {

    private final String key;          // rate-limit key (e.g. client IP or API key)
    private final long retryAfterMs;   // milliseconds until the client can retry

    public RateLimitExceededException(String key, long retryAfterMs) {
        super("Rate limit exceeded for key: " + key + " (retry after " + retryAfterMs + "ms)");
        this.key = key;
        this.retryAfterMs = retryAfterMs;
    }

    public String getKey() {
        return key;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
