package com.systemdesign.cache.model;

/**
 * CacheConfig — Configuration object with Builder pattern.
 *
 * WHY a config object instead of passing 6 params everywhere?
 * ------------------------------------------------------------
 * Without CacheConfig:
 *   new CacheService(store, eviction, stats, 1000, 300, EvictionPolicy.LRU, "consistent", 3, 150);
 *   // What's 1000? What's 300? What's 3? What's 150?
 *
 * With CacheConfig:
 *   CacheConfig config = CacheConfig.builder()
 *       .maxSize(1000)
 *       .defaultTtlSeconds(300)
 *       .evictionPolicy(EvictionPolicy.LRU)
 *       .replicationFactor(3)
 *       .build();
 *   new CacheService(store, eviction, stats, config);
 *   // Self-documenting. Easy to add new config fields without changing constructors.
 *
 * WIRING: AppConfig.createCacheService() builds CacheConfig → passes to CacheService constructor.
 */
public class CacheConfig {

    /**
     * EvictionPolicy enum — determines which eviction strategy is used.
     *
     * WITHOUT strategy pattern (ugly if-else in CacheService):
     *   if (policy == "LRU") {
     *       // 50 lines of LRU logic mixed into CacheService
     *   } else if (policy == "LFU") {
     *       // 50 lines of LFU logic mixed into CacheService
     *   } else if (policy == "TTL") {
     *       // 50 lines of TTL logic mixed into CacheService
     *   }
     *
     * WITH strategy pattern:
     *   evictionStrategy.evict();  // one line, polymorphism handles the rest
     */
    public enum EvictionPolicy {
        LRU,    // Least Recently Used — evicts the key that hasn't been accessed for the longest time
        LFU,    // Least Frequently Used — evicts the key with the lowest access count
        TTL     // Time To Live — evicts expired keys first
    }

    private final int maxSize;              // max number of entries in the cache
    private final long defaultTtlSeconds;   // default TTL for entries without explicit TTL (0 = no expiry)
    private final EvictionPolicy evictionPolicy;
    private final String hashingStrategy;   // "consistent" or "mod"
    private final int replicationFactor;    // how many nodes hold a copy of each key
    private final int numVirtualNodes;      // virtual nodes per physical node on the hash ring

    private CacheConfig(Builder builder) {
        this.maxSize = builder.maxSize;
        this.defaultTtlSeconds = builder.defaultTtlSeconds;
        this.evictionPolicy = builder.evictionPolicy;
        this.hashingStrategy = builder.hashingStrategy;
        this.replicationFactor = builder.replicationFactor;
        this.numVirtualNodes = builder.numVirtualNodes;
    }

    public static Builder builder() {
        return new Builder();
    }

    // --- Getters ---
    public int getMaxSize() { return maxSize; }
    public long getDefaultTtlSeconds() { return defaultTtlSeconds; }
    public EvictionPolicy getEvictionPolicy() { return evictionPolicy; }
    public String getHashingStrategy() { return hashingStrategy; }
    public int getReplicationFactor() { return replicationFactor; }
    public int getNumVirtualNodes() { return numVirtualNodes; }

    @Override
    public String toString() {
        return String.format("CacheConfig{maxSize=%d, ttl=%ds, policy=%s, hashing=%s, replicas=%d, vnodes=%d}",
                maxSize, defaultTtlSeconds, evictionPolicy, hashingStrategy, replicationFactor, numVirtualNodes);
    }

    public static class Builder {
        private int maxSize = 1000;
        private long defaultTtlSeconds = 0; // 0 = no default TTL
        private EvictionPolicy evictionPolicy = EvictionPolicy.LRU;
        private String hashingStrategy = "consistent";
        private int replicationFactor = 3;
        private int numVirtualNodes = 150;

        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder defaultTtlSeconds(long defaultTtlSeconds) {
            this.defaultTtlSeconds = defaultTtlSeconds;
            return this;
        }

        public Builder evictionPolicy(EvictionPolicy evictionPolicy) {
            this.evictionPolicy = evictionPolicy;
            return this;
        }

        public Builder hashingStrategy(String hashingStrategy) {
            this.hashingStrategy = hashingStrategy;
            return this;
        }

        public Builder replicationFactor(int replicationFactor) {
            this.replicationFactor = replicationFactor;
            return this;
        }

        public Builder numVirtualNodes(int numVirtualNodes) {
            this.numVirtualNodes = numVirtualNodes;
            return this;
        }

        public CacheConfig build() {
            if (maxSize <= 0) {
                throw new IllegalArgumentException("maxSize must be positive, got: " + maxSize);
            }
            return new CacheConfig(this);
        }
    }
}
