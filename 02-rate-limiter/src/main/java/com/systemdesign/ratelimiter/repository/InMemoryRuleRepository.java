package com.systemdesign.ratelimiter.repository;

import com.systemdesign.ratelimiter.model.RateLimitRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of RuleRepository.
 * Suitable for single-node demos; replace with Redis/DB for distributed systems.
 */
public class InMemoryRuleRepository implements RuleRepository {

    private final Map<String, RateLimitRule> rules = new ConcurrentHashMap<>();

    @Override
    public Optional<RateLimitRule> findByKey(String key) {
        return Optional.ofNullable(rules.get(key));
    }

    @Override
    public List<RateLimitRule> findAll() {
        return new ArrayList<>(rules.values());
    }

    @Override
    public void save(RateLimitRule rule) {
        rules.put(rule.getKey(), rule);
    }

    @Override
    public void delete(String key) {
        rules.remove(key);
    }

    @Override
    public boolean exists(String key) {
        return rules.containsKey(key);
    }
}
