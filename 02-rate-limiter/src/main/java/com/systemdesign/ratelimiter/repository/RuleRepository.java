package com.systemdesign.ratelimiter.repository;

import com.systemdesign.ratelimiter.model.RateLimitRule;

import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for rate limit rules.
 * In production, this could be backed by Redis, a database, or a config service.
 * Here we use an in-memory implementation for demonstration.
 */
public interface RuleRepository {

    Optional<RateLimitRule> findByKey(String key);

    List<RateLimitRule> findAll();

    void save(RateLimitRule rule);

    void delete(String key);

    boolean exists(String key);
}
