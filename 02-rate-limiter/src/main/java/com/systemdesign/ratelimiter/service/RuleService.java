package com.systemdesign.ratelimiter.service;

import com.systemdesign.ratelimiter.exception.RuleNotFoundException;
import com.systemdesign.ratelimiter.model.RateLimitRule;
import com.systemdesign.ratelimiter.repository.RuleRepository;

import java.util.List;

/**
 * CRUD operations for rate limit rules.
 * Separates rule management from rate-limiting logic (Single Responsibility).
 */
public class RuleService {

    private final RuleRepository repository;

    public RuleService(RuleRepository repository) {
        this.repository = repository;
    }

    public void createRule(RateLimitRule rule) {
        if (rule.getKey() == null || rule.getKey().isBlank()) {
            throw new IllegalArgumentException("Rule key cannot be blank");
        }
        repository.save(rule);
    }

    public RateLimitRule getRule(String key) {
        return repository.findByKey(key)
                .orElseThrow(() -> new RuleNotFoundException("No rule found for key: " + key));
    }

    public List<RateLimitRule> getAllRules() {
        return repository.findAll();
    }

    public void deleteRule(String key) {
        if (!repository.exists(key)) {
            throw new RuleNotFoundException("Cannot delete — no rule found for key: " + key);
        }
        repository.delete(key);
    }

    public void updateRule(String key, RateLimitRule updated) {
        if (!repository.exists(key)) {
            throw new RuleNotFoundException("Cannot update — no rule found for key: " + key);
        }
        repository.save(updated);
    }
}
