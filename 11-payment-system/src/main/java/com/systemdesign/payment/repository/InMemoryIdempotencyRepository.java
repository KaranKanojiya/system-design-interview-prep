package com.systemdesign.payment.repository;

import com.systemdesign.payment.model.IdempotencyRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryIdempotencyRepository — ConcurrentHashMap-backed idempotency storage.
 *
 * In production this would be Redis with TTL (auto-expiry after 24 hours).
 * Here we rely on IdempotencyService.cleanup() to remove expired records.
 */
public class InMemoryIdempotencyRepository implements IdempotencyRepository {

    private final Map<String, IdempotencyRecord> store = new ConcurrentHashMap<>();

    @Override
    public void save(IdempotencyRecord record) {
        store.put(record.getKey(), record);
    }

    @Override
    public Optional<IdempotencyRecord> findByKey(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void deleteByKey(String key) {
        store.remove(key);
    }

    @Override
    public List<IdempotencyRecord> findAll() {
        return new ArrayList<>(store.values());
    }
}
