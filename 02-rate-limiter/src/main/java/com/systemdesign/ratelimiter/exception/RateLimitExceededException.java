package com.systemdesign.ratelimiter.exception;

/**
 * Thrown when a client exceeds their rate limit.
 * Carries metadata useful for building a 429 response.
 */
public class RateLimitExceededException extends RuntimeException {

    private final String key;
    private final long retryAfterMs;

    public RateLimitExceededException(String key, long retryAfterMs) {
        super("Rate limit exceeded for key '%s'. Retry after %dms.".formatted(key, retryAfterMs));
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
