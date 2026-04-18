package com.systemdesign.ratelimiter.config;

import com.systemdesign.ratelimiter.controller.RateLimiterController;
import com.systemdesign.ratelimiter.model.Algorithm;
import com.systemdesign.ratelimiter.model.RateLimitRule;
import com.systemdesign.ratelimiter.repository.InMemoryRuleRepository;
import com.systemdesign.ratelimiter.repository.RuleRepository;
import com.systemdesign.ratelimiter.service.RateLimiterService;
import com.systemdesign.ratelimiter.strategy.*;

import java.util.EnumMap;
import java.util.Map;

/**
 * Application configuration — wires dependencies together.
 * In production, a DI framework (Spring, Guice) would handle this.
 * Here we use manual wiring to keep it framework-free and transparent.
 */
public class AppConfig {

    // --- Defaults ---
    public static final int DEFAULT_MAX_REQUESTS = 10;
    public static final long DEFAULT_WINDOW_MS = 60_000; // 1 minute
    public static final Algorithm DEFAULT_ALGORITHM = Algorithm.TOKEN_BUCKET;

    private AppConfig() {} // utility class

    /** Creates all 5 strategies, maps them by algorithm. */
    public static Map<Algorithm, RateLimiterStrategy> createStrategies() {
        Map<Algorithm, RateLimiterStrategy> strategies = new EnumMap<>(Algorithm.class);
        strategies.put(Algorithm.TOKEN_BUCKET, new TokenBucketStrategy());
        strategies.put(Algorithm.LEAKY_BUCKET, new LeakyBucketStrategy());
        strategies.put(Algorithm.FIXED_WINDOW, new FixedWindowCounterStrategy());
        strategies.put(Algorithm.SLIDING_WINDOW_LOG, new SlidingWindowLogStrategy());
        strategies.put(Algorithm.SLIDING_WINDOW_COUNTER, new SlidingWindowCounterStrategy());
        return strategies;
    }

    /** Creates a default rule used when no per-key rule is configured. */
    public static RateLimitRule createDefaultRule() {
        return RateLimitRule.builder("default", DEFAULT_MAX_REQUESTS, DEFAULT_WINDOW_MS)
                .algorithm(DEFAULT_ALGORITHM)
                .build();
    }

    /** Factory: fully-wired RateLimiterService. */
    public static RateLimiterService createDefaultService() {
        RuleRepository repository = new InMemoryRuleRepository();
        Map<Algorithm, RateLimiterStrategy> strategies = createStrategies();
        RateLimitRule defaultRule = createDefaultRule();
        return new RateLimiterService(repository, strategies, defaultRule);
    }

    /** Factory: controller backed by a default service. */
    public static RateLimiterController createController() {
        RateLimiterService service = createDefaultService();
        return new RateLimiterController(service);
    }
}
