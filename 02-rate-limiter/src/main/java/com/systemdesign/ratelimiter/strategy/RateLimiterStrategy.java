package com.systemdesign.ratelimiter.strategy;

import com.systemdesign.ratelimiter.model.Algorithm;
import com.systemdesign.ratelimiter.model.RateLimitResult;
import com.systemdesign.ratelimiter.model.RateLimitRule;

/**
 * Strategy interface for rate limiting algorithms.
 * Each implementation encapsulates one algorithm's state and logic.
 *
 * Design pattern: Strategy — allows swapping algorithms at runtime without changing clients.
 */
public interface RateLimiterStrategy {

    /** Attempt to consume one token/slot for the given key under the given rule. */
    RateLimitResult tryConsume(String key, RateLimitRule rule);

    /** Reset all counters/state for the given key. */
    void reset(String key);

    /** Which algorithm this strategy implements. */
    Algorithm algorithm();
}
