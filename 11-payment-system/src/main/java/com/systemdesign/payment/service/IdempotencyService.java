package com.systemdesign.payment.service;

import com.systemdesign.payment.model.IdempotencyRecord;
import com.systemdesign.payment.model.PaymentStatus;
import com.systemdesign.payment.repository.IdempotencyRepository;

import java.util.Optional;

/**
 * IdempotencyService — Prevents duplicate payment processing on client retries.
 *
 * THE PROBLEM:
 *   Client sends a payment request.  Network times out.  Client retries.
 *   Without idempotency, we charge the customer TWICE.  This is the #1
 *   complaint in payment systems and the #1 question interviewers ask.
 *
 * THE SOLUTION:
 *   1. Client generates a unique idempotency key (UUID) and sends it with every request
 *   2. On first request: we process the payment and store (key → result) in the cache
 *   3. On retry (same key): we skip processing and return the cached result
 *
 * RACE CONDITION:
 *   Two threads arrive with the same key simultaneously:
 *     Thread A: checkAndGet("key-1") → empty (not found)
 *     Thread B: checkAndGet("key-1") → empty (not found)  ← BOTH pass the check!
 *     Both threads process the payment → DOUBLE CHARGE!
 *
 *   FIX: The checkAndGet() method is synchronized.  Only one thread at a time
 *   can check-and-potentially-store.  Thread B will see the record that Thread A
 *   stored and return the cached result.
 *
 * EXPIRY:
 *   Records expire after 24 hours (matching Stripe's behavior).
 *   After expiry, the same key can be reused for a new payment.
 *   cleanup() removes expired records — call periodically.
 */
public class IdempotencyService {

    private final IdempotencyRepository repository;

    // Lock object for synchronized check-and-store
    // WHY a separate lock object instead of "synchronized(this)"?
    //   Best practice: using a dedicated private lock prevents external code
    //   from accidentally synchronizing on the same object and causing deadlocks.
    private final Object lock = new Object();

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
    }

    /**
     * Check if an idempotency key has been used before.
     *
     * SYNCHRONIZED to prevent the race condition where two threads with the
     * same key both pass the "not found" check and both process the payment.
     *
     * @param key the idempotency key from the client
     * @return Optional containing the cached record if key was already used
     */
    public Optional<IdempotencyRecord> checkAndGet(String key) {
        synchronized (lock) {
            Optional<IdempotencyRecord> existing = repository.findByKey(key);
            if (existing.isPresent() && !existing.get().isExpired()) {
                return existing;
            }
            // If expired, remove it so the key can be reused
            if (existing.isPresent() && existing.get().isExpired()) {
                repository.deleteByKey(key);
            }
            return Optional.empty();
        }
    }

    /**
     * Record a new idempotency key → result mapping.
     * Called after a payment is successfully processed (or failed).
     *
     * Also synchronized to ensure atomicity with checkAndGet().
     */
    public void record(String key, String paymentId, PaymentStatus status, String responseBody) {
        synchronized (lock) {
            IdempotencyRecord record = new IdempotencyRecord(key, paymentId, status, responseBody);
            repository.save(record);
        }
    }

    /**
     * Clean up expired idempotency records.
     * Should be called periodically (e.g. every hour) by a background job.
     *
     * WHY not rely on TTL?
     *   In-memory storage doesn't have built-in TTL like Redis.
     *   We simulate it with this cleanup method.
     */
    public void cleanup() {
        synchronized (lock) {
            for (IdempotencyRecord record : repository.findAll()) {
                if (record.isExpired()) {
                    repository.deleteByKey(record.getKey());
                }
            }
        }
    }
}
