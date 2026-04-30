package com.systemdesign.cache.model;

import java.time.LocalDateTime;

/**
 * CacheEntry — Generic cache entry with Builder pattern.
 *
 * WHY Builder pattern?
 * --------------------
 * CacheEntry has 7 fields. Without Builder, you'd need either:
 *   (a) A constructor with 7 params — unreadable, easy to swap args
 *       new CacheEntry("k", val, now, now, now.plusMinutes(5), 0, 128L)  // which DateTime is which??
 *   (b) Multiple constructor overloads — combinatorial explosion
 *   (c) Setters everywhere — object can be in an inconsistent state mid-construction
 *
 * Builder solves all three: readable, flexible, and the object is fully constructed before use.
 *
 * WIRING: AppConfig never creates CacheEntry directly. CacheService.put() creates entries
 * via CacheEntry.builder(), then passes them to CacheStore.put() and EvictionStrategy.onPut().
 */
public class CacheEntry {

    // --- Core fields ---
    private final String key;
    private final Object value;

    // --- Temporal fields ---
    private final LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;   // mutable — updated on every access
    private final LocalDateTime expiresAt;  // null means "never expires"

    // --- Metrics fields ---
    private int frequency;      // how many times this key has been accessed (used by LFU)
    private final long sizeBytes;    // estimated size in bytes (for memory-aware eviction)

    // ===========================================================================================
    // Private constructor — only Builder can create instances.
    // This is the whole point: you can't accidentally create a half-baked CacheEntry.
    // ===========================================================================================
    private CacheEntry(Builder builder) {
        this.key = builder.key;
        this.value = builder.value;
        this.createdAt = builder.createdAt;
        this.lastAccessedAt = builder.lastAccessedAt;
        this.expiresAt = builder.expiresAt;
        this.frequency = builder.frequency;
        this.sizeBytes = builder.sizeBytes;
    }

    // ===========================================================================================
    // Static factory method to kick off the builder chain.
    // Usage: CacheEntry.builder().key("user:1").value(user).ttlSeconds(300).build()
    // ===========================================================================================
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Check if this entry has expired.
     * Used by TTLEvictionStrategy for lazy expiration:
     *   - On get(), if isExpired() → return null, remove from store
     *   - This avoids a background thread scanning every entry
     */
    public boolean isExpired() {
        if (expiresAt == null) {
            return false; // no TTL set → never expires
        }
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Touch this entry — update lastAccessedAt and bump frequency.
     *
     * Called by CacheService.get() → EvictionStrategy.onGet() uses this to track access patterns.
     * - LRU uses lastAccessedAt (via moving node to head of linked list)
     * - LFU uses frequency (via moving key to next frequency bucket)
     */
    public void touch() {
        this.lastAccessedAt = LocalDateTime.now();
        this.frequency++;
    }

    // --- Getters ---
    public String getKey() { return key; }
    public Object getValue() { return value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastAccessedAt() { return lastAccessedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public int getFrequency() { return frequency; }
    public long getSizeBytes() { return sizeBytes; }

    @Override
    public String toString() {
        return String.format("CacheEntry{key='%s', value=%s, freq=%d, expired=%s}",
                key, value, frequency, isExpired());
    }

    // ===========================================================================================
    // Builder — static inner class
    //
    // WITHOUT Builder (ugly version):
    //   CacheEntry entry = new CacheEntry("user:1", userData, LocalDateTime.now(),
    //       LocalDateTime.now(), LocalDateTime.now().plusSeconds(300), 0, 256L);
    //   // Q: Is the 3rd arg createdAt or lastAccessedAt? Is 0 the frequency or something else?
    //
    // WITH Builder (clean version):
    //   CacheEntry entry = CacheEntry.builder()
    //       .key("user:1")
    //       .value(userData)
    //       .ttlSeconds(300)
    //       .sizeBytes(256L)
    //       .build();
    //   // Self-documenting. Can't mix up args. Defaults are sane.
    // ===========================================================================================
    public static class Builder {
        private String key;
        private Object value;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime lastAccessedAt = LocalDateTime.now();
        private LocalDateTime expiresAt = null; // default: no expiry
        private int frequency = 0;
        private long sizeBytes = 0L;

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder value(Object value) {
            this.value = value;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder lastAccessedAt(LocalDateTime lastAccessedAt) {
            this.lastAccessedAt = lastAccessedAt;
            return this;
        }

        public Builder expiresAt(LocalDateTime expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        /**
         * Convenience method: set TTL in seconds from now.
         * Internally converts to an absolute expiresAt timestamp.
         * Why absolute? So we don't need to remember "when was the TTL set?"
         */
        public Builder ttlSeconds(long ttlSeconds) {
            if (ttlSeconds > 0) {
                this.expiresAt = LocalDateTime.now().plusSeconds(ttlSeconds);
            }
            return this;
        }

        public Builder frequency(int frequency) {
            this.frequency = frequency;
            return this;
        }

        public Builder sizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
            return this;
        }

        /**
         * Build the CacheEntry.
         * Validates that key is not null — everything else has sensible defaults.
         */
        public CacheEntry build() {
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException("Cache entry key cannot be null or empty");
            }
            return new CacheEntry(this);
        }
    }
}
