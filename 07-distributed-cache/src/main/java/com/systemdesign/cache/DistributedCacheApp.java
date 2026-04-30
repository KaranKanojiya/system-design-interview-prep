package com.systemdesign.cache;

import com.systemdesign.cache.config.AppConfig;
import com.systemdesign.cache.controller.CacheController;
import com.systemdesign.cache.display.CacheStatsDisplay;
import com.systemdesign.cache.model.CacheConfig.EvictionPolicy;
import com.systemdesign.cache.model.CacheNode;
import com.systemdesign.cache.model.CacheStats;
import com.systemdesign.cache.service.CacheService;
import com.systemdesign.cache.service.EvictionService;
import com.systemdesign.cache.service.ReplicationService;
import com.systemdesign.cache.store.NodeAwareCacheStore;
import com.systemdesign.cache.strategy.eviction.LFUEvictionStrategy;
import com.systemdesign.cache.strategy.hashing.ConsistentHashStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DistributedCacheApp — Main demo application showcasing the distributed cache system.
 *
 * Runs 9 demos that exercise every component:
 *   Demo 1: Basic Cache Operations (put, get, delete)
 *   Demo 2: LRU Eviction in Action (fill cache, show eviction order)
 *   Demo 3: LFU Eviction in Action (access patterns, frequency-based eviction)
 *   Demo 4: TTL Expiration (set TTL, wait, show expiry)
 *   Demo 5: Consistent Hashing (key distribution, node add/remove, redistribution)
 *   Demo 6: Hot Key Problem (one key accessed 1000x)
 *   Demo 7: Cache Stampede Simulation (multiple threads hitting same missing key)
 *   Demo 8: Cache Stats & Hit Rate
 *   Demo 9: Strategy Comparison (same workload with LRU vs LFU, compare hit rates)
 *
 * WIRING:
 *   main() creates AppConfig → AppConfig creates all objects → demos use them
 *   AppConfig is the ONLY place that says "new ConcreteClass()"
 */
public class DistributedCacheApp {

    private static final String SEPARATOR = "=".repeat(70);
    private static final String THIN_SEP = "-".repeat(70);

    private final AppConfig appConfig;
    private final CacheStatsDisplay statsDisplay;

    public DistributedCacheApp() {
        this.appConfig = new AppConfig();
        this.statsDisplay = appConfig.createCacheStatsDisplay();
    }

    public static void main(String[] args) throws Exception {
        DistributedCacheApp app = new DistributedCacheApp();

        System.out.println(SEPARATOR);
        System.out.println("   DISTRIBUTED CACHE — System Design Interview Demo");
        System.out.println("   Java 21, Plain Java, No Frameworks");
        System.out.println(SEPARATOR);
        System.out.println();

        app.demo1_BasicCacheOperations();
        app.demo2_LRUEviction();
        app.demo3_LFUEviction();
        app.demo4_TTLExpiration();
        app.demo5_ConsistentHashing();
        app.demo6_HotKeyProblem();
        app.demo7_CacheStampede();
        app.demo8_CacheStatsAndHitRate();
        app.demo9_StrategyComparison();

        app.printDesignSummary();
    }

    // ===========================================================================================
    // DEMO 1: Basic Cache Operations
    // Shows: CacheController → CacheService → CacheStore flow
    // ===========================================================================================
    private void demo1_BasicCacheOperations() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 1: Basic Cache Operations (Put, Get, Delete)");
        System.out.println(SEPARATOR);
        System.out.println("  Demonstrates the core cache API: put a value, get it back, delete it.");
        System.out.println("  Call chain: Controller → Service → Store → EvictionStrategy (tracking)");
        System.out.println();

        CacheController controller = appConfig.createCacheController(EvictionPolicy.LRU, 100);
        System.out.println();

        // Put some values
        controller.handlePut("user:1", "Alice");
        controller.handlePut("user:2", "Bob");
        controller.handlePut("user:3", "Charlie");
        System.out.println();

        // Get values (should be cache hits)
        controller.handleGet("user:1");
        controller.handleGet("user:2");
        System.out.println();

        // Get a non-existent key (cache miss)
        controller.handleGet("user:999");
        System.out.println();

        // Delete a key
        controller.handleDelete("user:2");
        System.out.println();

        // Try to get deleted key (cache miss)
        controller.handleGet("user:2");
        System.out.println();

        // Show stats
        controller.handleStats();
        System.out.println();
    }

    // ===========================================================================================
    // DEMO 2: LRU Eviction
    // Shows: HashMap + DoublyLinkedList, O(1) eviction of least recently used key
    // ===========================================================================================
    private void demo2_LRUEviction() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 2: LRU Eviction in Action");
        System.out.println(SEPARATOR);
        System.out.println("  Cache capacity = 5. Insert 5 items, then insert more to trigger eviction.");
        System.out.println("  LRU evicts the key that hasn't been accessed for the longest time.");
        System.out.println("  Data structure: HashMap + Doubly Linked List (O(1) for all operations).");
        System.out.println();

        CacheService cache = appConfig.createCacheService(EvictionPolicy.LRU, 5);
        System.out.println();

        // Fill the cache to capacity
        System.out.println("  --- Filling cache to capacity (5 items) ---");
        for (int i = 1; i <= 5; i++) {
            cache.put("key" + i, "value" + i);
            System.out.printf("  PUT key%d → cache size: %d%n", i, cache.size());
        }
        System.out.println();

        // Access key1 and key2 to make them "recently used"
        System.out.println("  --- Accessing key1 and key2 (moves them to head of LRU list) ---");
        cache.get("key1");
        System.out.println("  GET key1 → moved to head (most recently used)");
        cache.get("key2");
        System.out.println("  GET key2 → moved to head (most recently used)");
        System.out.println();

        // LRU order is now (most recent → least recent): key2, key1, key5, key4, key3
        System.out.println("  LRU order (most→least recent): key2, key1, key5, key4, key3");
        System.out.println();

        // Insert a new key → should evict key3 (least recently used)
        System.out.println("  --- Inserting key6 (cache full → must evict) ---");
        cache.put("key6", "value6");
        System.out.printf("  PUT key6 → cache size: %d%n", cache.size());
        System.out.println();

        // Verify: key3 should be evicted
        System.out.println("  --- Verifying eviction ---");
        Object val3 = cache.get("key3");
        System.out.printf("  GET key3 → %s (should be null — evicted!)%n", val3);
        Object val1 = cache.get("key1");
        System.out.printf("  GET key1 → %s (should be value1 — was recently accessed)%n", val1);
        Object val6 = cache.get("key6");
        System.out.printf("  GET key6 → %s (should be value6 — just inserted)%n", val6);
        System.out.println();

        // Insert two more → should evict key4, then key5
        System.out.println("  --- Inserting key7 and key8 (two more evictions) ---");
        cache.put("key7", "value7");
        System.out.printf("  PUT key7 → evicts key4 (next least recently used)%n");
        cache.put("key8", "value8");
        System.out.printf("  PUT key8 → evicts key5%n");
        System.out.println();

        Object val4 = cache.get("key4");
        Object val5 = cache.get("key5");
        System.out.printf("  GET key4 → %s (evicted)%n", val4);
        System.out.printf("  GET key5 → %s (evicted)%n", val5);
        System.out.println();

        System.out.printf("  Final cache size: %d, Evictions: %d%n",
                cache.size(), cache.getStats().getEvictions());
        System.out.println();
    }

    // ===========================================================================================
    // DEMO 3: LFU Eviction
    // Shows: Frequency buckets with LinkedHashSet, O(1) eviction of least frequently used key
    // ===========================================================================================
    private void demo3_LFUEviction() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 3: LFU Eviction in Action");
        System.out.println(SEPARATOR);
        System.out.println("  Cache capacity = 5. Keys with fewer accesses get evicted first.");
        System.out.println("  LFU tracks frequency (access count) per key. Ties broken by LRU order.");
        System.out.println("  Data structure: keyToFreq + freqToKeys(LinkedHashSet) (O(1) for all ops).");
        System.out.println();

        CacheService cache = appConfig.createCacheService(EvictionPolicy.LFU, 5);
        System.out.println();

        // Fill the cache
        System.out.println("  --- Filling cache with different access patterns ---");
        cache.put("popular", "I am accessed a lot");
        cache.put("moderate", "I am accessed sometimes");
        cache.put("rare1", "I am rarely accessed");
        cache.put("rare2", "I am also rarely accessed");
        cache.put("rare3", "I am rarely accessed too");
        System.out.printf("  Cache size: %d (full)%n", cache.size());
        System.out.println();

        // Create different access frequencies
        System.out.println("  --- Creating access patterns ---");
        for (int i = 0; i < 10; i++) {
            cache.get("popular");     // accessed 10 times
        }
        System.out.println("  'popular' accessed 10 times (freq=11 including initial put)");

        for (int i = 0; i < 5; i++) {
            cache.get("moderate");    // accessed 5 times
        }
        System.out.println("  'moderate' accessed 5 times (freq=6 including initial put)");

        cache.get("rare1");           // accessed 1 time
        System.out.println("  'rare1' accessed 1 time (freq=2 including initial put)");

        // rare2 and rare3: accessed 0 additional times (freq=1 from initial put only)
        System.out.println("  'rare2' and 'rare3' never accessed after put (freq=1)");
        System.out.println();

        // Now insert a new key → should evict rare2 (lowest freq, earliest insertion at freq=1)
        System.out.println("  --- Inserting 'newcomer' (cache full → LFU eviction) ---");
        cache.put("newcomer", "I just arrived");
        System.out.println();

        System.out.println("  --- Verifying which key was evicted ---");
        System.out.printf("  'popular' → %s (freq=11, should survive)%n", cache.get("popular") != null ? "HIT" : "MISS");
        System.out.printf("  'moderate' → %s (freq=6, should survive)%n", cache.get("moderate") != null ? "HIT" : "MISS");
        System.out.printf("  'rare1' → %s (freq=2, should survive)%n", cache.get("rare1") != null ? "HIT" : "MISS");
        System.out.printf("  'rare2' → %s (freq=1, EVICTED — lowest freq, first in at freq 1)%n",
                cache.get("rare2") != null ? "HIT" : "MISS");
        System.out.printf("  'rare3' → %s (freq=1, survived — rare2 was inserted first at freq 1)%n",
                cache.get("rare3") != null ? "HIT" : "MISS");
        System.out.printf("  'newcomer' → %s (just added)%n", cache.get("newcomer") != null ? "HIT" : "MISS");
        System.out.println();

        // Show LFU frequency tracking
        if (cache.getEvictionStrategy() instanceof LFUEvictionStrategy lfu) {
            System.out.println("  --- LFU Frequency Tracker ---");
            System.out.printf("  'popular' freq:  %d%n", lfu.getFrequency("popular"));
            System.out.printf("  'moderate' freq: %d%n", lfu.getFrequency("moderate"));
            System.out.printf("  'rare1' freq:    %d%n", lfu.getFrequency("rare1"));
            System.out.printf("  'newcomer' freq: %d%n", lfu.getFrequency("newcomer"));
            System.out.printf("  Min frequency:   %d%n", lfu.getMinFrequency());
        }
        System.out.println();
    }

    // ===========================================================================================
    // DEMO 4: TTL Expiration
    // Shows: Lazy expiration on get() + active cleanup via TreeMap expiration index
    // ===========================================================================================
    private void demo4_TTLExpiration() throws InterruptedException {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 4: TTL Expiration (Time To Live)");
        System.out.println(SEPARATOR);
        System.out.println("  Keys expire after their TTL. Expired keys are removed lazily on access.");
        System.out.println("  Two approaches: lazy (check on get) vs active (periodic sweep).");
        System.out.println();

        CacheService cache = appConfig.createCacheService(EvictionPolicy.TTL, 100);
        System.out.println();

        // Put entries with different TTLs
        System.out.println("  --- Inserting keys with different TTLs ---");
        cache.put("short-lived", "I expire in 1 second", 1);
        cache.put("medium-lived", "I expire in 3 seconds", 3);
        cache.put("long-lived", "I expire in 60 seconds", 60);
        cache.put("immortal", "I never expire", 0);
        System.out.println("  'short-lived'  TTL=1s");
        System.out.println("  'medium-lived' TTL=3s");
        System.out.println("  'long-lived'   TTL=60s");
        System.out.println("  'immortal'     TTL=none");
        System.out.println();

        // Immediately check — all should be present
        System.out.println("  --- Checking immediately (all should be present) ---");
        System.out.printf("  'short-lived'  → %s%n", cache.get("short-lived") != null ? "HIT" : "MISS");
        System.out.printf("  'medium-lived' → %s%n", cache.get("medium-lived") != null ? "HIT" : "MISS");
        System.out.printf("  'long-lived'   → %s%n", cache.get("long-lived") != null ? "HIT" : "MISS");
        System.out.printf("  'immortal'     → %s%n", cache.get("immortal") != null ? "HIT" : "MISS");
        System.out.println();

        // Wait for short-lived to expire
        System.out.println("  --- Waiting 1.5 seconds... ---");
        Thread.sleep(1500);

        System.out.println("  --- Checking after 1.5s (short-lived should be expired) ---");
        System.out.printf("  'short-lived'  → %s (TTL expired — lazy expiration on get!)%n",
                cache.get("short-lived") != null ? "HIT" : "MISS");
        System.out.printf("  'medium-lived' → %s (still alive)%n",
                cache.get("medium-lived") != null ? "HIT" : "MISS");
        System.out.printf("  'long-lived'   → %s (still alive)%n",
                cache.get("long-lived") != null ? "HIT" : "MISS");
        System.out.printf("  'immortal'     → %s (no TTL → never expires)%n",
                cache.get("immortal") != null ? "HIT" : "MISS");
        System.out.println();

        // Wait for medium-lived to expire
        System.out.println("  --- Waiting 2 more seconds... ---");
        Thread.sleep(2000);

        System.out.println("  --- Checking after 3.5s (medium-lived should be expired) ---");
        System.out.printf("  'medium-lived' → %s (TTL expired!)%n",
                cache.get("medium-lived") != null ? "HIT" : "MISS");
        System.out.printf("  'long-lived'   → %s (TTL=60s, still alive)%n",
                cache.get("long-lived") != null ? "HIT" : "MISS");
        System.out.printf("  'immortal'     → %s (lives forever)%n",
                cache.get("immortal") != null ? "HIT" : "MISS");
        System.out.println();
    }

    // ===========================================================================================
    // DEMO 5: Consistent Hashing
    // Shows: TreeMap ring, virtual nodes, MD5 hash, key distribution, node add/remove
    // ===========================================================================================
    private void demo5_ConsistentHashing() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 5: Consistent Hashing — Key Distribution & Rebalancing");
        System.out.println(SEPARATOR);
        System.out.println("  Consistent hashing maps keys to nodes on a ring. Virtual nodes improve");
        System.out.println("  distribution. When nodes are added/removed, only ~1/N keys move.");
        System.out.println();

        // Create consistent hashing with 150 virtual nodes per physical node
        ConsistentHashStrategy consistentHash = appConfig.createConsistentHashStrategy(150);
        System.out.println();

        // Add 3 nodes
        System.out.println("  --- Adding 3 nodes to the hash ring ---");
        CacheNode node1 = new CacheNode("cache-east", "10.0.1.1", 6379);
        CacheNode node2 = new CacheNode("cache-west", "10.0.2.1", 6379);
        CacheNode node3 = new CacheNode("cache-central", "10.0.3.1", 6379);
        consistentHash.addNode(node1);
        consistentHash.addNode(node2);
        consistentHash.addNode(node3);
        System.out.printf("  Ring size: %d virtual nodes (3 physical × 150 virtual)%n",
                consistentHash.getRingSize());
        System.out.println();

        // Generate test keys and show distribution
        List<String> testKeys = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            testKeys.add("user:" + i);
        }

        System.out.println("  --- Distribution of 1000 keys across 3 nodes ---");
        Map<String, Integer> dist3 = consistentHash.getKeyDistribution(testKeys);
        statsDisplay.printNodeDistribution(dist3);
        System.out.println();

        // Record which node each key maps to BEFORE adding a node
        List<CacheNode> nodes3 = List.of(node1, node2, node3);
        Map<String, String> keyToNodeBefore = new java.util.HashMap<>();
        for (String key : testKeys) {
            keyToNodeBefore.put(key, consistentHash.getNode(key, nodes3).getNodeId());
        }

        // Add a 4th node
        System.out.println("  --- Adding 4th node (cache-south) ---");
        CacheNode node4 = new CacheNode("cache-south", "10.0.4.1", 6379);
        consistentHash.addNode(node4);
        System.out.println();

        System.out.println("  --- Distribution of 1000 keys across 4 nodes ---");
        Map<String, Integer> dist4 = consistentHash.getKeyDistribution(testKeys);
        statsDisplay.printNodeDistribution(dist4);
        System.out.println();

        // Count how many keys moved
        List<CacheNode> nodes4 = List.of(node1, node2, node3, node4);
        int keysMoved = 0;
        for (String key : testKeys) {
            String newNode = consistentHash.getNode(key, nodes4).getNodeId();
            if (!keyToNodeBefore.get(key).equals(newNode)) {
                keysMoved++;
            }
        }
        System.out.printf("  Keys that moved when adding node: %d / %d (%.1f%%)%n",
                keysMoved, testKeys.size(), (double) keysMoved / testKeys.size() * 100);
        System.out.printf("  Ideal (1/N): %.1f%% — consistent hashing minimizes data movement!%n",
                100.0 / 4);
        System.out.println();

        // Remove a node and show redistribution
        System.out.println("  --- Removing node (cache-west) ---");
        Map<String, String> keyToNodeBefore4 = new java.util.HashMap<>();
        for (String key : testKeys) {
            keyToNodeBefore4.put(key, consistentHash.getNode(key, nodes4).getNodeId());
        }

        consistentHash.removeNode(node2);
        System.out.println();

        List<CacheNode> nodes3After = List.of(node1, node3, node4);
        Map<String, Integer> distAfterRemoval = consistentHash.getKeyDistribution(testKeys);
        System.out.println("  --- Distribution after removing cache-west ---");
        statsDisplay.printNodeDistribution(distAfterRemoval);

        int keysMovedAfterRemoval = 0;
        for (String key : testKeys) {
            String newNode = consistentHash.getNode(key, nodes3After).getNodeId();
            if (!keyToNodeBefore4.get(key).equals(newNode)) {
                keysMovedAfterRemoval++;
            }
        }
        System.out.printf("  Keys that moved when removing node: %d / %d (%.1f%%)%n",
                keysMovedAfterRemoval, testKeys.size(), (double) keysMovedAfterRemoval / testKeys.size() * 100);
        System.out.println();
    }

    // ===========================================================================================
    // DEMO 6: Hot Key Problem
    // Shows: One key accessed disproportionately causes load imbalance
    // ===========================================================================================
    private void demo6_HotKeyProblem() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 6: Hot Key Problem");
        System.out.println(SEPARATOR);
        System.out.println("  A 'hot key' is a key accessed far more than others (e.g., a viral tweet).");
        System.out.println("  In a distributed cache, the node storing this key becomes a bottleneck.");
        System.out.println("  Solutions: local caching, key splitting, read replicas.");
        System.out.println();

        CacheService cache = appConfig.createCacheService(EvictionPolicy.LFU, 100);
        System.out.println();

        // Insert several keys
        System.out.println("  --- Inserting keys with varied access patterns ---");
        cache.put("viral-tweet", "This tweet went viral with 10M views");
        cache.put("normal-post-1", "Regular post 1");
        cache.put("normal-post-2", "Regular post 2");
        cache.put("normal-post-3", "Regular post 3");
        System.out.println();

        // Simulate hot key: viral tweet accessed 1000 times
        System.out.println("  --- Simulating hot key: 'viral-tweet' accessed 1000 times ---");
        for (int i = 0; i < 1000; i++) {
            cache.get("viral-tweet");
        }

        // Normal keys accessed a few times
        for (int i = 0; i < 5; i++) {
            cache.get("normal-post-1");
            cache.get("normal-post-2");
            cache.get("normal-post-3");
        }
        System.out.println();

        // Show the frequency disparity
        if (cache.getEvictionStrategy() instanceof LFUEvictionStrategy lfu) {
            System.out.println("  --- Access Frequency Analysis ---");
            System.out.printf("  'viral-tweet'   frequency: %d   *** HOT KEY ***%n", lfu.getFrequency("viral-tweet"));
            System.out.printf("  'normal-post-1' frequency: %d%n", lfu.getFrequency("normal-post-1"));
            System.out.printf("  'normal-post-2' frequency: %d%n", lfu.getFrequency("normal-post-2"));
            System.out.printf("  'normal-post-3' frequency: %d%n", lfu.getFrequency("normal-post-3"));
        }
        System.out.println();

        System.out.println("  --- Hot Key Mitigation Strategies ---");
        System.out.println("  1. Local cache: Each app server caches the hot key locally (L1 cache)");
        System.out.println("  2. Key splitting: Split 'viral-tweet' into 'viral-tweet:1', 'viral-tweet:2', ...");
        System.out.println("     Each shard goes to a different node, reads are distributed randomly.");
        System.out.println("  3. Read replicas: Replicate the hot key's node, route reads to replicas.");
        System.out.println();

        // Show stats
        CacheStats stats = cache.getStats();
        System.out.printf("  Total requests: %d, Hit rate: %.2f%%%n",
                stats.getTotalRequests(), stats.getHitRate() * 100);
        System.out.println();
    }

    // ===========================================================================================
    // DEMO 7: Cache Stampede Simulation
    // Shows: Multiple threads request the same missing key simultaneously
    // ===========================================================================================
    private void demo7_CacheStampede() throws InterruptedException {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 7: Cache Stampede Simulation (Thundering Herd)");
        System.out.println(SEPARATOR);
        System.out.println("  A cache stampede occurs when a popular key expires and many concurrent");
        System.out.println("  requests all miss the cache simultaneously, flooding the database.");
        System.out.println("  AKA 'thundering herd' or 'dog-pile effect'.");
        System.out.println();
        System.out.println("  Mitigation strategies:");
        System.out.println("  1. Mutex/lock: Only one thread fetches from DB, others wait.");
        System.out.println("  2. Probabilistic early expiration: Refresh before actual expiry.");
        System.out.println("  3. Stale-while-revalidate: Serve stale data while refreshing in background.");
        System.out.println();

        CacheService cache = appConfig.createCacheService(EvictionPolicy.LRU, 100);
        System.out.println();

        // Simulate: key is NOT in cache, 10 threads request it simultaneously
        int numThreads = 10;
        String hotKey = "popular-product:42";

        System.out.printf("  --- %d threads requesting missing key '%s' simultaneously ---%n",
                numThreads, hotKey);
        System.out.println("  (In a real system, each miss = a database query. 10 threads = 10 DB queries!)");
        System.out.println();

        CountDownLatch startLatch = new CountDownLatch(1);  // synchronize thread start
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // all threads start at the same time

                    Object value = cache.get(hotKey);
                    if (value == null) {
                        // Cache miss! In a real system, this thread would query the database.
                        System.out.printf("  Thread-%d: CACHE MISS for '%s' → would query database!%n",
                                threadId, hotKey);

                        // Simulate "fetching from database" and populating cache
                        // WITHOUT a lock, ALL threads do this — wasteful!
                        cache.put(hotKey, "Product 42 data (fetched by thread-" + threadId + ")");
                    } else {
                        System.out.printf("  Thread-%d: CACHE HIT for '%s' → %s%n",
                                threadId, hotKey, value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        System.out.println();
        System.out.printf("  Result: %d cache misses (= %d redundant DB queries!)%n",
                cache.getStats().getMisses(), cache.getStats().getMisses());
        System.out.println("  With a mutex, only 1 thread would query the DB, others would wait.");
        System.out.println();
    }

    // ===========================================================================================
    // DEMO 8: Cache Stats & Hit Rate
    // Shows: AtomicLong counters, hit rate calculation
    // ===========================================================================================
    private void demo8_CacheStatsAndHitRate() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 8: Cache Stats & Hit Rate Analysis");
        System.out.println(SEPARATOR);
        System.out.println("  A healthy cache should have 80%+ hit rate. Below 50% = not effective.");
        System.out.println("  Stats use AtomicLong for thread-safe counting.");
        System.out.println();

        CacheService cache = appConfig.createCacheService(EvictionPolicy.LRU, 50);
        System.out.println();

        // Phase 1: Warm-up (all puts, then gets for the same keys → high hit rate)
        System.out.println("  --- Phase 1: Warm-up (populate cache, then read same keys) ---");
        for (int i = 0; i < 50; i++) {
            cache.put("item:" + i, "value-" + i);
        }
        System.out.printf("  Inserted %d items%n", cache.size());

        // Read existing keys → should be 100% hit rate
        for (int i = 0; i < 50; i++) {
            cache.get("item:" + i);
        }
        System.out.printf("  Phase 1 stats: %d hits, %d misses, hit rate = %.2f%%%n",
                cache.getStats().getHits(), cache.getStats().getMisses(),
                cache.getStats().getHitRate() * 100);
        System.out.println();

        // Phase 2: Working set larger than cache (50 cache slots, 100 keys → ~50% hit rate)
        System.out.println("  --- Phase 2: Working set > cache size (thrashing) ---");
        cache.getStats().reset();
        cache.clear();

        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 100; i++) {
                // Try to get → if miss → put
                Object val = cache.get("item:" + i);
                if (val == null) {
                    cache.put("item:" + i, "value-" + i);
                }
            }
        }

        System.out.printf("  Phase 2 stats: %d hits, %d misses, hit rate = %.2f%%%n",
                cache.getStats().getHits(), cache.getStats().getMisses(),
                cache.getStats().getHitRate() * 100);
        System.out.println("  Low hit rate! Working set (100 keys) > cache size (50). Cache thrashing.");
        System.out.println("  Fix: increase cache size, or use a smarter eviction policy (LFU).");
        System.out.println();

        statsDisplay.printStats(cache.getStats());
        System.out.println();
    }

    // ===========================================================================================
    // DEMO 9: Strategy Comparison
    // Shows: Same workload with LRU vs LFU, compare hit rates
    // ===========================================================================================
    private void demo9_StrategyComparison() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 9: Strategy Comparison — LRU vs LFU");
        System.out.println(SEPARATOR);
        System.out.println("  Same workload, same cache size, different eviction policies.");
        System.out.println("  Workload: 20 'hot' keys (80% of requests) + 80 'cold' keys (20% of requests).");
        System.out.println("  This mimics Pareto/Zipf distribution common in real-world workloads.");
        System.out.println();

        int cacheSize = 30; // smaller than total keys (100), but bigger than hot keys (20)

        // --- LRU test ---
        System.out.println("  --- Testing LRU ---");
        CacheService lruCache = appConfig.createCacheService(EvictionPolicy.LRU, cacheSize);
        runWorkload(lruCache);
        CacheStats lruStats = lruCache.getStats();
        System.out.printf("  LRU: hits=%d, misses=%d, hit rate=%.2f%%\n",
                lruStats.getHits(), lruStats.getMisses(), lruStats.getHitRate() * 100);
        System.out.println();

        // --- LFU test ---
        System.out.println("  --- Testing LFU ---");
        CacheService lfuCache = appConfig.createCacheService(EvictionPolicy.LFU, cacheSize);
        runWorkload(lfuCache);
        CacheStats lfuStats = lfuCache.getStats();
        System.out.printf("  LFU: hits=%d, misses=%d, hit rate=%.2f%%\n",
                lfuStats.getHits(), lfuStats.getMisses(), lfuStats.getHitRate() * 100);
        System.out.println();

        // --- Comparison ---
        System.out.println(THIN_SEP);
        System.out.println("  COMPARISON RESULTS");
        System.out.println(THIN_SEP);
        System.out.printf("  %-6s  Hits: %-6d  Misses: %-6d  Hit Rate: %.2f%%%n",
                "LRU", lruStats.getHits(), lruStats.getMisses(), lruStats.getHitRate() * 100);
        System.out.printf("  %-6s  Hits: %-6d  Misses: %-6d  Hit Rate: %.2f%%%n",
                "LFU", lfuStats.getHits(), lfuStats.getMisses(), lfuStats.getHitRate() * 100);
        System.out.println();

        double lruRate = lruStats.getHitRate();
        double lfuRate = lfuStats.getHitRate();
        if (lfuRate > lruRate) {
            System.out.printf("  LFU wins by %.2f%% — it keeps the frequently-accessed hot keys%n",
                    (lfuRate - lruRate) * 100);
            System.out.println("  even when cold keys push them toward eviction.");
        } else if (lruRate > lfuRate) {
            System.out.printf("  LRU wins by %.2f%% — recency was more predictive than frequency.%n",
                    (lruRate - lfuRate) * 100);
        } else {
            System.out.println("  Tie! Both strategies perform equally on this workload.");
        }
        System.out.println();
        System.out.println("  KEY INSIGHT: LFU excels with skewed distributions (Zipf/Pareto).");
        System.out.println("  LRU excels with temporal locality (recently accessed = likely accessed again).");
        System.out.println("  Real systems often use LRU (simpler, good enough) or hybrid (e.g., Redis's LFU).");
        System.out.println();
    }

    /**
     * Run a workload that simulates Zipf distribution:
     * 80% of accesses go to 20% of keys (hot keys).
     */
    private void runWorkload(CacheService cache) {
        int numHotKeys = 20;
        int numColdKeys = 80;
        int totalRequests = 5000;

        // Seed all keys
        for (int i = 0; i < numHotKeys + numColdKeys; i++) {
            cache.put("key:" + i, "value-" + i);
        }

        // Reset stats after seeding
        cache.getStats().reset();

        // Generate workload: 80% hot, 20% cold
        java.util.Random random = new java.util.Random(42); // fixed seed for reproducibility
        for (int i = 0; i < totalRequests; i++) {
            String key;
            if (random.nextDouble() < 0.8) {
                // Hot key access (80% of requests → 20% of keys)
                key = "key:" + random.nextInt(numHotKeys);
            } else {
                // Cold key access (20% of requests → 80% of keys)
                key = "key:" + (numHotKeys + random.nextInt(numColdKeys));
            }

            Object val = cache.get(key);
            if (val == null) {
                cache.put(key, "value-refreshed-" + key);
            }
        }
    }

    // ===========================================================================================
    // Design Summary — Print all patterns, components, and design decisions used.
    // ===========================================================================================
    private void printDesignSummary() {
        System.out.println(SEPARATOR);
        System.out.println("  DESIGN SUMMARY — Distributed Cache");
        System.out.println(SEPARATOR);
        System.out.println();

        System.out.println("  DESIGN PATTERNS USED:");
        System.out.println(THIN_SEP);
        System.out.println("  1. Strategy Pattern");
        System.out.println("     - EvictionStrategy interface → LRU, LFU, TTL implementations");
        System.out.println("     - HashingStrategy interface → ConsistentHash, ModHash implementations");
        System.out.println("     - Swap algorithms at runtime without changing CacheService code");
        System.out.println();
        System.out.println("  2. Builder Pattern");
        System.out.println("     - CacheEntry.Builder — 7 fields, self-documenting construction");
        System.out.println("     - CacheConfig.Builder — 6 config fields, immutable after build");
        System.out.println();
        System.out.println("  3. Factory Pattern");
        System.out.println("     - AppConfig is the factory — creates and wires ALL objects");
        System.out.println("     - createCacheService(), createDistributedCacheService()");
        System.out.println("     - The ONLY place that says 'new ConcreteClass()'");
        System.out.println();
        System.out.println("  4. Facade Pattern");
        System.out.println("     - CacheService is the facade — wraps store + eviction + stats");
        System.out.println("     - Callers only interact with CacheService, not internal components");
        System.out.println();
        System.out.println("  5. Repository Pattern");
        System.out.println("     - CacheRepository interface → InMemoryCacheRepository implementation");
        System.out.println("     - Separates cache storage from persistent backing store");
        System.out.println();

        System.out.println("  DATA STRUCTURES:");
        System.out.println(THIN_SEP);
        System.out.println("  - LRU: HashMap + Doubly Linked List (O(1) get, put, evict)");
        System.out.println("  - LFU: keyToFreq Map + freqToKeys Map<freq, LinkedHashSet> (O(1) all ops)");
        System.out.println("  - TTL: TreeMap<expiryTime, Set<keys>> for ordered expiration");
        System.out.println("  - Consistent Hashing: TreeMap<hash, VirtualNode> ring with MD5 hash");
        System.out.println("  - Storage: ConcurrentHashMap for thread-safe in-memory storage");
        System.out.println();

        System.out.println("  DISTRIBUTED SYSTEM CONCEPTS:");
        System.out.println(THIN_SEP);
        System.out.println("  - Consistent Hashing: minimizes key redistribution on node changes");
        System.out.println("  - Virtual Nodes: improves hash ring distribution uniformity");
        System.out.println("  - Replication: data copied to follower nodes for fault tolerance");
        System.out.println("  - Cache Stampede: thundering herd when popular key expires");
        System.out.println("  - Hot Keys: single key overwhelms one node (Zipf distribution)");
        System.out.println("  - TTL Expiration: lazy (on access) vs active (periodic sweep)");
        System.out.println();

        System.out.println("  COMPONENT ARCHITECTURE:");
        System.out.println(THIN_SEP);
        System.out.println("  Controller → Service → Store → ConcurrentHashMap");
        System.out.println("                 ↓                    ↑");
        System.out.println("          EvictionStrategy     HashingStrategy");
        System.out.println("          (LRU/LFU/TTL)       (ConsistentHash/Mod)");
        System.out.println("                 ↓");
        System.out.println("            CacheStats          ReplicationService");
        System.out.println();

        System.out.println(SEPARATOR);
        System.out.println("  End of Distributed Cache Demo");
        System.out.println(SEPARATOR);
    }
}
