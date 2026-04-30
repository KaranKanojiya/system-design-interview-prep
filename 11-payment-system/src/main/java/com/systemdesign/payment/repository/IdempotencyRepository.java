package com.systemdesign.payment.repository;

import com.systemdesign.payment.model.IdempotencyRecord;

import java.util.List;
import java.util.Optional;

/**
 * IdempotencyRepository — Data access interface for IdempotencyRecord entities.
 *
 * The idempotency key is the primary lookup key (not a generated ID).
 * In production this would be a Redis store with TTL for auto-expiry.
 */
public interface IdempotencyRepository {
    void save(IdempotencyRecord record);
    Optional<IdempotencyRecord> findByKey(String key);
    void deleteByKey(String key);
    List<IdempotencyRecord> findAll();
}
