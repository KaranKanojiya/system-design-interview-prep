package com.systemdesign.ratelimiter.service;

import com.systemdesign.ratelimiter.model.*;
import com.systemdesign.ratelimiter.repository.InMemoryRuleRepository;
import com.systemdesign.ratelimiter.repository.RuleRepository;
import com.systemdesign.ratelimiter.strategy.*;

import java.util.EnumMap;
import java.util.Map;

/**
 * Core service that orchestrates rate limiting.
 * Resolves the rule for each request, selects the appropriate strategy, and returns the result.
 *
 * Design patterns used:
 *   - Strategy: algorithm selection at runtime
 *   - Repository: decoupled rule storage
 *   - Factory Method: createDefault() for convenient wiring
 */
public class RateLimiterService {

    private final RuleRepository ruleRepository;
    private final Map<Algorithm, RateLimiterStrategy> strategies;
    private final RateLimitRule defaultRule;

    public RateLimiterService(RuleRepository ruleRepository,
                              Map<Algorithm, RateLimiterStrategy> strategies,
                              RateLimitRule defaultRule) {
        this.ruleRepository = ruleRepository;
        this.strategies = strategies;
        this.defaultRule = defaultRule;
    }

    /**
     * Main entry point: check if a request should be allowed or throttled.
     */
    public RateLimitResult checkRateLimit(RequestContext context) {
        String key = context.getRateLimitKey();

        // Look up rule; fall back to default if no rule is configured for this key
        RateLimitRule rule = ruleRepository.findByKey(key).orElse(defaultRule);

        // Disabled rules always allow traffic through
        if (!rule.isEnabled()) {
            return RateLimitResult.allowed(rule.getMaxRequests(), rule.getMaxRequests(), 0);
        }

        // Dispatch to the correct algorithm strategy
        RateLimiterStrategy strategy = strategies.get(rule.getAlgorithm());
        if (strategy == null) {
            throw new IllegalStateException("No strategy registered for algorithm: " + rule.getAlgorithm());
        }

        return strategy.tryConsume(key, rule);
    }

    /** Reset counters across all strategies for a given key. */
    public void resetLimit(String key) {
        strategies.values().forEach(strategy -> strategy.reset(key));
    }

    public RuleRepository getRuleRepository() {
        return ruleRepository;
    }

    /**
     * Factory method: creates a fully-wired RateLimiterService with all 5 algorithms
     * and a sensible default rule (10 req/min, Token Bucket).
     */
    public static RateLimiterService createDefault() {
        Map<Algorithm, RateLimiterStrategy> strategies = new EnumMap<>(Algorithm.class);
        strategies.put(Algorithm.TOKEN_BUCKET, new TokenBucketStrategy());
        strategies.put(Algorithm.LEAKY_BUCKET, new LeakyBucketStrategy());
        strategies.put(Algorithm.FIXED_WINDOW, new FixedWindowCounterStrategy());
        strategies.put(Algorithm.SLIDING_WINDOW_LOG, new SlidingWindowLogStrategy());
        strategies.put(Algorithm.SLIDING_WINDOW_COUNTER, new SlidingWindowCounterStrategy());

        RateLimitRule defaultRule = RateLimitRule.builder("default", 10, 60_000)
                .algorithm(Algorithm.TOKEN_BUCKET)
                .build();

        return new RateLimiterService(new InMemoryRuleRepository(), strategies, defaultRule);
    }
}
