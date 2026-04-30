package com.systemdesign.cache.config;

import com.systemdesign.cache.controller.CacheController;
import com.systemdesign.cache.display.CacheStatsDisplay;
import com.systemdesign.cache.model.CacheConfig;
import com.systemdesign.cache.model.CacheConfig.EvictionPolicy;
import com.systemdesign.cache.model.CacheNode;
import com.systemdesign.cache.model.CacheStats;
import com.systemdesign.cache.repository.CacheRepository;
import com.systemdesign.cache.repository.InMemoryCacheRepository;
import com.systemdesign.cache.service.CacheService;
import com.systemdesign.cache.service.EvictionService;
import com.systemdesign.cache.service.ReplicationService;
import com.systemdesign.cache.store.CacheStore;
import com.systemdesign.cache.store.InMemoryCacheStore;
import com.systemdesign.cache.store.NodeAwareCacheStore;
import com.systemdesign.cache.strategy.eviction.EvictionStrategy;
import com.systemdesign.cache.strategy.eviction.LFUEvictionStrategy;
import com.systemdesign.cache.strategy.eviction.LRUEvictionStrategy;
import com.systemdesign.cache.strategy.eviction.TTLEvictionStrategy;
import com.systemdesign.cache.strategy.hashing.ConsistentHashStrategy;
import com.systemdesign.cache.strategy.hashing.HashingStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * AppConfig — FACTORY — Creates and wires ALL objects. The ONLY place that says "new ConcreteClass()".
 *
 * WHY a factory class?
 * --------------------
 * Without a central factory, object creation is scattered everywhere:
 *   // In main():
 *   CacheStore store = new InMemoryCacheStore();
 *   EvictionStrategy eviction = new LRUEvictionStrategy(1000);
 *   CacheStats stats = new CacheStats();
 *   CacheConfig config = CacheConfig.builder().maxSize(1000).build();
 *   CacheService service = new CacheService(store, eviction, stats, config);
 *   CacheStatsDisplay display = new CacheStatsDisplay();
 *   CacheController controller = new CacheController(service, display);
 *   // 7 lines of wiring in main(). Hard to see the dependency graph.
 *   // If you need the same wiring in tests, you duplicate all of it.
 *
 * With AppConfig:
 *   AppConfig config = new AppConfig();
 *   CacheController controller = config.createCacheController(EvictionPolicy.LRU, 10);
 *   // One line. AppConfig knows the dependency graph.
 *
 * DEPENDENCY GRAPH (what creates what, what injects into what):
 *
 *   AppConfig
 *     ├── creates CacheConfig (Builder pattern)
 *     ├── creates CacheStats
 *     ├── creates EvictionService
 *     │     ├── creates LRUEvictionStrategy(maxSize)
 *     │     ├── creates LFUEvictionStrategy(maxSize)
 *     │     └── creates TTLEvictionStrategy(maxSize)
 *     ├── creates InMemoryCacheStore  (or NodeAwareCacheStore for distributed mode)
 *     │     └── (distributed) creates ConsistentHashStrategy(numVirtualNodes)
 *     │     └── (distributed) creates CacheNode instances
 *     ├── creates CacheService(store, evictionStrategy, stats, config)   ← FACADE
 *     ├── creates CacheStatsDisplay
 *     ├── creates CacheController(cacheService, statsDisplay)
 *     ├── creates CacheRepository (InMemoryCacheRepository)
 *     └── creates ReplicationService(replicationFactor)
 */
public class AppConfig {

    // ===========================================================================================
    // createCacheService — Single-node cache service.
    //
    // Creates: CacheConfig → EvictionStrategy → CacheStore → CacheStats → CacheService
    //
    // Call chain: DistributedCacheApp → AppConfig.createCacheService()
    //   → builds CacheConfig with Builder
    //   → creates eviction strategy based on config
    //   → creates InMemoryCacheStore
    //   → creates CacheStats
    //   → wires everything into CacheService
    // ===========================================================================================
    public CacheService createCacheService(EvictionPolicy policy, int maxSize) {
        System.out.println("  [AppConfig] Creating single-node CacheService...");

        // 1. Build configuration
        CacheConfig config = CacheConfig.builder()
                .maxSize(maxSize)
                .evictionPolicy(policy)
                .defaultTtlSeconds(0) // no default TTL
                .build();
        System.out.println("  [AppConfig] Config: " + config);

        // 2. Create the eviction strategy based on the configured policy
        EvictionStrategy evictionStrategy = createEvictionStrategy(policy, maxSize);
        System.out.printf("  [AppConfig] Eviction strategy: %s%n", evictionStrategy.getEvictionPolicyName());

        // 3. Create the store (single-node = simple InMemoryCacheStore)
        CacheStore store = new InMemoryCacheStore();
        System.out.println("  [AppConfig] Store: InMemoryCacheStore (ConcurrentHashMap-backed)");

        // 4. Create stats tracker
        CacheStats stats = new CacheStats();

        // 5. Wire everything into CacheService (the facade)
        CacheService cacheService = new CacheService(store, evictionStrategy, stats, config);
        System.out.println("  [AppConfig] CacheService created and wired.");

        return cacheService;
    }

    /**
     * Create a CacheService with a specific TTL default.
     */
    public CacheService createCacheServiceWithTTL(EvictionPolicy policy, int maxSize, long defaultTtlSeconds) {
        System.out.println("  [AppConfig] Creating CacheService with TTL...");

        CacheConfig config = CacheConfig.builder()
                .maxSize(maxSize)
                .evictionPolicy(policy)
                .defaultTtlSeconds(defaultTtlSeconds)
                .build();

        EvictionStrategy evictionStrategy = createEvictionStrategy(policy, maxSize);
        CacheStore store = new InMemoryCacheStore();
        CacheStats stats = new CacheStats();

        return new CacheService(store, evictionStrategy, stats, config);
    }

    // ===========================================================================================
    // createDistributedCacheService — Multi-node cache service with consistent hashing.
    //
    // Creates: CacheConfig → CacheNodes → ConsistentHashStrategy → NodeAwareCacheStore
    //        → EvictionStrategy → CacheStats → CacheService
    //
    // Additional wiring for distributed mode:
    //   → ConsistentHashStrategy.addNode() for each CacheNode
    //   → NodeAwareCacheStore wraps per-node InMemoryCacheStores
    //   → ReplicationService for simulated replication
    // ===========================================================================================
    public CacheService createDistributedCacheService(int numNodes, int maxSizePerNode, int numVirtualNodes) {
        System.out.println("  [AppConfig] Creating distributed CacheService...");

        // 1. Build configuration for distributed mode
        CacheConfig config = CacheConfig.builder()
                .maxSize(maxSizePerNode * numNodes) // total capacity across all nodes
                .evictionPolicy(EvictionPolicy.LRU)
                .hashingStrategy("consistent")
                .numVirtualNodes(numVirtualNodes)
                .replicationFactor(3)
                .build();
        System.out.println("  [AppConfig] Config: " + config);

        // 2. Create physical cache nodes
        List<CacheNode> nodes = new ArrayList<>();
        for (int i = 1; i <= numNodes; i++) {
            CacheNode node = new CacheNode(
                    "node-" + i,
                    "192.168.1." + (10 + i),
                    6379 + i
            );
            nodes.add(node);
            System.out.println("  [AppConfig] Created node: " + node);
        }

        // 3. Create consistent hashing strategy and register nodes
        ConsistentHashStrategy hashingStrategy = new ConsistentHashStrategy(numVirtualNodes);
        for (CacheNode node : nodes) {
            hashingStrategy.addNode(node);
        }

        // 4. Create node-aware store (routes to correct node via consistent hashing)
        NodeAwareCacheStore store = new NodeAwareCacheStore(hashingStrategy, nodes);
        System.out.println("  [AppConfig] NodeAwareCacheStore created with " + numNodes + " nodes");

        // 5. Create eviction strategy and stats
        EvictionStrategy evictionStrategy = new LRUEvictionStrategy(maxSizePerNode * numNodes);
        CacheStats stats = new CacheStats();

        // 6. Wire into CacheService
        CacheService cacheService = new CacheService(store, evictionStrategy, stats, config);
        System.out.println("  [AppConfig] Distributed CacheService created and wired.");

        return cacheService;
    }

    // ===========================================================================================
    // createCacheController — Creates the controller with its dependencies.
    //
    // Dependency chain:
    //   AppConfig → CacheService (via createCacheService)
    //             → CacheStatsDisplay (new)
    //             → CacheController(cacheService, statsDisplay)
    // ===========================================================================================
    public CacheController createCacheController(EvictionPolicy policy, int maxSize) {
        CacheService cacheService = createCacheService(policy, maxSize);
        CacheStatsDisplay statsDisplay = new CacheStatsDisplay();
        return new CacheController(cacheService, statsDisplay);
    }

    /**
     * Create a CacheController wrapping an existing CacheService.
     */
    public CacheController createCacheController(CacheService cacheService) {
        CacheStatsDisplay statsDisplay = new CacheStatsDisplay();
        return new CacheController(cacheService, statsDisplay);
    }

    // ===========================================================================================
    // createEvictionService — Creates an EvictionService with all strategies registered.
    //
    // Wiring:
    //   AppConfig → creates LRUEvictionStrategy(maxSize)
    //             → creates LFUEvictionStrategy(maxSize)
    //             → creates TTLEvictionStrategy(maxSize)
    //             → registers all in EvictionService
    //             → sets active policy
    // ===========================================================================================
    public EvictionService createEvictionService(int maxSize, EvictionPolicy activePolicy) {
        System.out.println("  [AppConfig] Creating EvictionService with all strategies...");

        EvictionService evictionService = new EvictionService();

        // Register all available strategies
        evictionService.registerStrategy(EvictionPolicy.LRU, new LRUEvictionStrategy(maxSize));
        evictionService.registerStrategy(EvictionPolicy.LFU, new LFUEvictionStrategy(maxSize));
        evictionService.registerStrategy(EvictionPolicy.TTL, new TTLEvictionStrategy(maxSize));

        // Set the active policy
        evictionService.setActivePolicy(activePolicy);

        return evictionService;
    }

    /**
     * Create a ReplicationService.
     */
    public ReplicationService createReplicationService(int replicationFactor) {
        return new ReplicationService(replicationFactor);
    }

    /**
     * Create a CacheRepository (backing store).
     */
    public CacheRepository createCacheRepository() {
        return new InMemoryCacheRepository();
    }

    /**
     * Create a CacheStatsDisplay.
     */
    public CacheStatsDisplay createCacheStatsDisplay() {
        return new CacheStatsDisplay();
    }

    /**
     * Create a ConsistentHashStrategy (exposed for direct use in demos).
     */
    public ConsistentHashStrategy createConsistentHashStrategy(int numVirtualNodes) {
        return new ConsistentHashStrategy(numVirtualNodes);
    }

    // ===========================================================================================
    // Private helper — creates the right eviction strategy for a given policy.
    //
    // WITHOUT this factory method (ugly):
    //   // Scattered across the codebase:
    //   EvictionStrategy strategy;
    //   if (policy == EvictionPolicy.LRU) {
    //       strategy = new LRUEvictionStrategy(maxSize);
    //   } else if (policy == EvictionPolicy.LFU) {
    //       strategy = new LFUEvictionStrategy(maxSize);
    //   } else {
    //       strategy = new TTLEvictionStrategy(maxSize);
    //   }
    //
    // WITH factory method (clean, centralized):
    //   EvictionStrategy strategy = createEvictionStrategy(policy, maxSize);
    // ===========================================================================================
    private EvictionStrategy createEvictionStrategy(EvictionPolicy policy, int maxSize) {
        return switch (policy) {
            case LRU -> new LRUEvictionStrategy(maxSize);
            case LFU -> new LFUEvictionStrategy(maxSize);
            case TTL -> new TTLEvictionStrategy(maxSize);
        };
    }
}
