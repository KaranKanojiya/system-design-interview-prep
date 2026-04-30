# Low-Level Design: Distributed Cache System

> **Difficulty**: HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: Consistent Hashing, Eviction Policies, Concurrency, Strategy Pattern
> This is a top-tier system design question. It tests data structures (LinkedHashMap, DoublyLinkedList, TreeMap), distributed systems concepts (consistent hashing, replication), and design pattern mastery (Strategy, Facade, Factory).

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Strategy Implementations](#6-strategy-implementations)
7. [Service Layer Design](#7-service-layer-design)
8. [Concurrency Considerations](#8-concurrency-considerations)
9. [SOLID Principles Applied](#9-solid-principles-applied)
10. [Sample Workflows](#10-sample-workflows)
11. [Design Patterns Used](#11-design-patterns-used)
12. [Extensibility Points](#12-extensibility-points)

---

## 1. Core Modules Overview

| Module | Package | Responsibility |
|--------|---------|----------------|
| **Model** | `model/` | Domain entities: CacheEntry (key-value with TTL, frequency, access time), CacheNode (physical node), VirtualNode (on consistent hashing ring), CacheConfig (max size, eviction policy, TTL defaults). |
| **Strategy (Eviction)** | `strategy/eviction/` | Pluggable eviction algorithms: LRU (HashMap + DoublyLinkedList, O(1)), LFU (HashMap + frequency buckets, O(1)), TTL-based (lazy + active cleanup). Strategy pattern -- swap eviction policy without touching cache logic. |
| **Strategy (Hashing)** | `strategy/hashing/` | Pluggable hashing algorithms: ConsistentHashStrategy (TreeMap + virtual nodes, minimizes key redistribution), ModHashStrategy (naive mod-based, for comparison/anti-pattern demo). |
| **Service** | `service/` | Business logic: CacheService (Facade -- wraps store + eviction + hashing + replication), EvictionService (manages eviction lifecycle), ReplicationService (conceptual node replication). |
| **Store** | `store/` | Storage abstraction: CacheStore interface, InMemoryCacheStore (ConcurrentHashMap-backed single node), NodeAwareCacheStore (routes to correct node via consistent hashing). |
| **Repository** | `repository/` | Data access layer: CacheRepository interface, InMemoryCacheRepository. Backing store abstraction if cache-aside pattern is needed. |
| **Controller** | `controller/` | REST-like API entry point: CacheController maps requests to CacheService calls. |
| **Config** | `config/` | Factory wiring: AppConfig creates all objects and injects dependencies. No framework -- pure constructor injection. |
| **Display** | `display/` | CacheStatsDisplay: hit/miss ratio, memory usage, per-node stats, eviction counts. |
| **Exception** | `exception/` | Domain exceptions: CacheException (base), CacheFullException, KeyNotFoundException, NodeUnavailableException. |

### Why Distributed Cache Is a Top-Tier Interview Question

```
Interviewer's checklist when evaluating your answer:

  1. Do you know LRU internals (not just "use LinkedHashMap")?    --> Data Structures
  2. Can you explain LFU with O(1) get and put?                   --> Algorithm Design
  3. Do you use consistent hashing (not naive mod)?               --> Distributed Systems
  4. Are virtual nodes used to avoid hotspots?                    --> Production Awareness
  5. Is eviction policy pluggable (Strategy pattern)?             --> OCP / Design Patterns
  6. Do you handle TTL with lazy + active expiration?             --> Real-World Caching
  7. Is your CacheStore thread-safe?                              --> Concurrency
  8. Can you add a new eviction policy without changing CacheService? --> Open-Closed
  9. Do you understand replication and its trade-offs?            --> CAP Theorem Awareness
  10. Is your Facade clean (CacheService hides complexity)?       --> Facade Pattern
```

---

## 2. Package Structure

```
com.systemdesign.cache
│
├── model/
│   ├── CacheEntry.java              -- Key-value with metadata: TTL, frequency, access time, size
│   ├── CacheNode.java               -- Physical cache node: nodeId, host, port, status
│   ├── VirtualNode.java             -- Virtual node on consistent hashing ring: hash, physicalNode
│   └── CacheConfig.java             -- Configuration: maxSize, evictionPolicy, defaultTTL, vnodeCount
│
├── strategy/
│   ├── eviction/
│   │   ├── EvictionStrategy.java         -- Interface: onGet, onPut, evict, remove, size, clear
│   │   ├── LRUEvictionStrategy.java      -- HashMap + DoublyLinkedList (O(1) get/put/evict)
│   │   ├── LFUEvictionStrategy.java      -- HashMap + frequency buckets (O(1) get/put/evict)
│   │   └── TTLEvictionStrategy.java      -- TTL-based: lazy expiration on access + active cleanup thread
│   │
│   └── hashing/
│       ├── HashingStrategy.java          -- Interface: getNode(key), addNode, removeNode
│       ├── ConsistentHashStrategy.java   -- TreeMap<Integer, VirtualNode>, 150 vnodes, MD5 hashing
│       └── ModHashStrategy.java          -- Naive key.hashCode() % nodeCount (anti-pattern demo)
│
├── service/
│   ├── CacheService.java            -- FACADE: get, put, delete, stats (orchestrates everything)
│   ├── EvictionService.java         -- Manages eviction lifecycle: check capacity, trigger eviction
│   └── ReplicationService.java      -- Replicates data to N successor nodes (conceptual)
│
├── store/
│   ├── CacheStore.java              -- Interface: get, put, delete, contains, size, clear
│   ├── InMemoryCacheStore.java      -- ConcurrentHashMap<String, CacheEntry> (single-node store)
│   └── NodeAwareCacheStore.java     -- Routes to correct CacheNode via HashingStrategy
│
├── repository/
│   ├── CacheRepository.java         -- Interface: loadFromBacking, writeToBacking (cache-aside)
│   └── InMemoryCacheRepository.java -- Simulates a backing store (DB, file, etc.)
│
├── controller/
│   └── CacheController.java         -- REST-like: get(key), put(key,value), delete(key), stats()
│
├── config/
│   └── AppConfig.java               -- Factory wiring: creates all objects, injects dependencies
│
├── display/
│   └── CacheStatsDisplay.java       -- Prints hit/miss ratio, eviction count, per-node distribution
│
├── exception/
│   ├── CacheException.java          -- Base exception for all cache errors
│   ├── CacheFullException.java      -- Cache at capacity and eviction cannot free space
│   ├── KeyNotFoundException.java    -- Key not found in cache (get/delete miss)
│   └── NodeUnavailableException.java -- Target node is down or unreachable
│
└── DistributedCacheApp.java         -- Main demo: wires everything, runs get/put/evict scenarios
```

---

## 3. Class Diagram

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                       EVICTION STRATEGY HIERARCHY (Strategy Pattern)             ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  EvictionStrategy<K>                |
    |-----------------------------------------------------------|
    | + onGet(key: K): void                                     |
    | + onPut(key: K): void                                     |
    | + evict(): Optional<K>                                    |
    | + remove(key: K): void                                    |
    | + size(): int                                             |
    | + clear(): void                                           |
    | + getStrategyName(): String                               |
    +-----------------------------------------------------------+
          ^                    ^                    ^
          |                    |                    |
    implements           implements           implements
          |                    |                    |
    +-----+----------+ +------+-----------+ +------+------------+
    | LRUEviction    | | LFUEviction      | | TTLEviction       |
    |   Strategy     | |   Strategy       | |   Strategy        |
    |----------------| |------------------| |-------------------|
    | -map: HashMap  | | -keyToNode:      | | -map: HashMap     |
    |  <K, DLLNode>  | |   HashMap<K,Node>| |  <K, Long>        |
    | -head: DLLNode | | -freqToBucket:   | | -expiryQueue:     |
    | -tail: DLLNode | |   HashMap<Int,   | |  PriorityQueue    |
    | -capacity: int | |   LinkedHashSet> | | -defaultTTL: long |
    |----------------| | -minFreq: int    | |-------------------|
    | +onGet: move   | | -capacity: int   | | +onGet: check     |
    |  to head       | |------------------| |  expiry           |
    | +onPut: add    | | +onGet: bump     | | +onPut: set       |
    |  to head       | |  frequency       | |  expiry time      |
    | +evict: remove | | +onPut: add at   | | +evict: remove    |
    |  tail          | |  freq=1          | |  earliest expiry  |
    +----------------+ | +evict: remove   | +-------------------+
                       |  from minFreq    |
                       |  bucket          |
                       +------------------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                       HASHING STRATEGY HIERARCHY (Strategy Pattern)              ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  HashingStrategy                    |
    |-----------------------------------------------------------|
    | + getNode(key: String): Optional<CacheNode>               |
    | + addNode(node: CacheNode): void                          |
    | + removeNode(node: CacheNode): Set<String>                |
    | + getNodeCount(): int                                     |
    | + getAllNodes(): List<CacheNode>                           |
    +-----------------------------------------------------------+
          ^                              ^
          |                              |
    implements                     implements
          |                              |
    +-----+------------------+ +---------+----------------+
    | ConsistentHash         | | ModHash                  |
    |   Strategy             | |   Strategy               |
    |------------------------| |--------------------------|
    | -ring: TreeMap          | | -nodes: List<CacheNode> |
    |   <Integer,VirtualNode>| |--------------------------|
    | -vnodeCount: int (150) | | +getNode: key.hash %    |
    | -nodeMap: Map<String,  | |   nodeCount              |
    |   CacheNode>           | | (ANTI-PATTERN: adding/   |
    |------------------------| |  removing node remaps    |
    | +getNode: ceiling/     | |  ALL keys)              |
    |  first on ring         | +--------------------------+
    | +addNode: place 150    |
    |  vnodes via MD5        |
    | +removeNode: remove    |
    |  vnodes, return        |
    |  affected keys         |
    +------------------------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                       STORE HIERARCHY                                            ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  CacheStore<K,V>                    |
    |-----------------------------------------------------------|
    | + get(key: K): Optional<CacheEntry<K,V>>                  |
    | + put(key: K, entry: CacheEntry<K,V>): void               |
    | + delete(key: K): boolean                                 |
    | + contains(key: K): boolean                               |
    | + size(): int                                             |
    | + clear(): void                                           |
    | + keys(): Set<K>                                          |
    +-----------------------------------------------------------+
          ^                              ^
          |                              |
    implements                     implements
          |                              |
    +-----+------------------+ +---------+------------------+
    | InMemoryCacheStore     | | NodeAwareCacheStore         |
    |------------------------| |----------------------------|
    | -store: Concurrent     | | -hashingStrategy:          |
    |   HashMap<K,CacheEntry>| |   HashingStrategy          |
    |------------------------| | -nodeStores: Map<String,   |
    | +get: direct lookup    | |   InMemoryCacheStore>      |
    | +put: direct insert    | |----------------------------|
    | +delete: direct remove | | +get: hash key -> find     |
    +------------------------+ |  node -> delegate to       |
                               |  node's InMemoryCacheStore |
                               | +put: same routing logic   |
                               +----------------------------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                       SERVICE LAYER (Facade + Dependencies)                      ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |           CacheService<K,V>  <<Facade>>                   |
    |-----------------------------------------------------------|
    | - store: CacheStore<K,V>                   [interface]     |
    | - evictionService: EvictionService<K>                      |
    | - replicationService: ReplicationService                   |
    | - config: CacheConfig                                     |
    | - hitCount: AtomicLong                                    |
    | - missCount: AtomicLong                                   |
    |-----------------------------------------------------------|
    | + get(key: K): Optional<V>                                |
    | + put(key: K, value: V): void                             |
    | + put(key: K, value: V, ttl: Duration): void              |
    | + delete(key: K): boolean                                 |
    | + getStats(): CacheStats                                  |
    | + clear(): void                                           |
    +-----------------------------------------------------------+
         |           |               |              |
         | uses      | uses          | uses         | uses
         v           v               v              v
    CacheStore   EvictionService  Replication    CacheConfig
                                  Service

    +-----------------------------------------------------------+
    |           EvictionService<K>                               |
    |-----------------------------------------------------------|
    | - strategy: EvictionStrategy<K>            [interface]     |
    | - maxSize: int                                            |
    |-----------------------------------------------------------|
    | + recordAccess(key: K): void                              |
    | + recordInsertion(key: K): void                           |
    | + evictIfNeeded(currentSize: int): Optional<K>            |
    | + forceEvict(): Optional<K>                               |
    | + recordRemoval(key: K): void                             |
    +-----------------------------------------------------------+
         |
         | delegates to
         v
    EvictionStrategy<K>  (LRU, LFU, or TTL)

    +-----------------------------------------------------------+
    |           ReplicationService                               |
    |-----------------------------------------------------------|
    | - hashingStrategy: HashingStrategy         [interface]     |
    | - replicaCount: int                                       |
    |-----------------------------------------------------------|
    | + replicatePut(key: String, entry: CacheEntry): void      |
    | + replicateDelete(key: String): void                      |
    | + getReplicaNodes(key: String): List<CacheNode>           |
    +-----------------------------------------------------------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                       REPOSITORY LAYER                                           ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  CacheRepository<K,V>              |
    |-----------------------------------------------------------|
    | + loadFromBacking(key: K): Optional<V>                    |
    | + writeToBacking(key: K, value: V): void                  |
    | + deleteFromBacking(key: K): boolean                      |
    | + existsInBacking(key: K): boolean                        |
    +-----------------------------------------------------------+
          ^
          | implements
    +-----+------------------------+
    | InMemoryCacheRepository      |
    |------------------------------|
    | -backingStore: ConcurrentHash|
    |   Map<K, V>                  |
    |------------------------------|
    | Simulates DB/file backing    |
    | store for cache-aside pattern|
    +------------------------------+

RELATIONSHIP SUMMARY
====================
CacheController       --uses-->  CacheService (Facade)
CacheService          --uses-->  CacheStore (interface)
CacheService          --uses-->  EvictionService
CacheService          --uses-->  ReplicationService
CacheService          --uses-->  CacheConfig
EvictionService       --uses-->  EvictionStrategy (interface)
NodeAwareCacheStore   --uses-->  HashingStrategy (interface)
NodeAwareCacheStore   --uses-->  Map<String, InMemoryCacheStore>
ReplicationService    --uses-->  HashingStrategy (interface)
ConsistentHashStrategy--uses-->  TreeMap<Integer, VirtualNode>
ConsistentHashStrategy--uses-->  CacheNode (physical nodes)
LRUEvictionStrategy   --uses-->  HashMap + DoublyLinkedList
LFUEvictionStrategy   --uses-->  HashMap + FrequencyBucket map
TTLEvictionStrategy   --uses-->  HashMap + PriorityQueue
AppConfig             --creates--> all objects, injects via constructors
CacheStatsDisplay     --reads-->   CacheService.getStats()
```

---

## 4. Entity Design

> This section defines every model class. Each is designed to be immutable where possible and carries metadata needed for eviction, hashing, and replication decisions.

### 4.1 CacheEntry (Generic, Metadata-Rich)

> **Core data structure**: Every value stored in the cache is wrapped in a CacheEntry. The metadata (TTL, frequency, access time) is what eviction strategies read to decide WHAT to evict.

```java
/**
 * Wraps a cached value with metadata needed by eviction strategies.
 *
 * WHY a wrapper instead of storing raw values?
 *   - LRU needs lastAccessedAt
 *   - LFU needs frequency
 *   - TTL needs createdAt + ttl
 *   - Stats need size estimation
 *
 * Without this wrapper, CacheStore would need to maintain separate metadata
 * maps for each eviction strategy -- violating SRP and making extension painful.
 *
 * @param <K> the key type (typically String)
 * @param <V> the value type (generic -- String, byte[], POJO, etc.)
 */
public class CacheEntry<K, V> {
    private final K key;
    private final V value;
    private final long createdAt;            // epoch millis
    private volatile long lastAccessedAt;    // epoch millis, updated on every get
    private final long ttlMillis;            // time-to-live in millis (0 = no expiry)
    private volatile int frequency;          // access count (for LFU)
    private final int estimatedSizeBytes;    // rough size estimate for memory tracking

    public CacheEntry(K key, V value, long ttlMillis) {
        Objects.requireNonNull(key, "key must not be null");
        this.key = key;
        this.value = value;
        this.createdAt = System.currentTimeMillis();
        this.lastAccessedAt = this.createdAt;
        this.ttlMillis = ttlMillis;
        this.frequency = 1;
        this.estimatedSizeBytes = estimateSize(value);
    }

    /**
     * Convenience constructor with no TTL (entry never expires based on time).
     */
    public CacheEntry(K key, V value) {
        this(key, value, 0L);
    }

    /**
     * Checks if this entry has expired based on TTL.
     *
     * Called by:
     *   - TTLEvictionStrategy.onGet() for lazy expiration
     *   - TTLEvictionStrategy cleanup thread for active expiration
     *   - InMemoryCacheStore.get() as a guard before returning stale data
     *
     * @return true if TTL is set AND current time > createdAt + ttl
     */
    public boolean isExpired() {
        if (ttlMillis <= 0) {
            return false;  // No TTL means never expires
        }
        return System.currentTimeMillis() > (createdAt + ttlMillis);
    }

    /**
     * Records an access: bumps frequency and updates lastAccessedAt.
     * Called by EvictionService.recordAccess() on every cache GET.
     */
    public void recordAccess() {
        this.lastAccessedAt = System.currentTimeMillis();
        this.frequency++;
    }

    /**
     * Returns the remaining time-to-live in milliseconds.
     * Returns -1 if no TTL is set, 0 if already expired.
     */
    public long getRemainingTtl() {
        if (ttlMillis <= 0) {
            return -1;  // No expiry
        }
        long remaining = (createdAt + ttlMillis) - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    // --- Size estimation ---
    private int estimateSize(V value) {
        if (value == null) return 0;
        if (value instanceof String s) return s.length() * 2;     // Java chars = 2 bytes
        if (value instanceof byte[] b) return b.length;
        return 64;  // default estimate for objects
    }

    // --- Getters ---
    public K getKey()                    { return key; }
    public V getValue()                  { return value; }
    public long getCreatedAt()           { return createdAt; }
    public long getLastAccessedAt()      { return lastAccessedAt; }
    public long getTtlMillis()           { return ttlMillis; }
    public int getFrequency()            { return frequency; }
    public int getEstimatedSizeBytes()   { return estimatedSizeBytes; }

    @Override
    public String toString() {
        return String.format("CacheEntry[key=%s, freq=%d, ttl=%dms, expired=%s]",
                key, frequency, ttlMillis, isExpired());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheEntry<?, ?> that = (CacheEntry<?, ?>) o;
        return key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }
}
```

**Interview point**: `lastAccessedAt` and `frequency` are `volatile` because multiple threads may read/write them concurrently. This is lightweight visibility guarantee without full synchronization. For stronger guarantees, see Section 8 on lock striping.

---

### 4.2 CacheNode (Physical Node)

> Represents a physical machine in the distributed cache cluster. Each node has a unique ID, a host:port address, and a status (UP/DOWN). The consistent hashing ring maps virtual nodes to these physical nodes.

```java
/**
 * Represents a physical cache server in the distributed cluster.
 *
 * In a real system, this would hold connection pool info, health check
 * timestamps, load metrics, etc. For interview purposes, we keep it focused
 * on the essentials needed for consistent hashing and replication.
 */
public class CacheNode {

    public enum NodeStatus {
        UP,           // Node is healthy and serving requests
        DOWN,         // Node is unreachable (health check failed)
        DRAINING      // Node is being removed, migrating data out
    }

    private final String nodeId;        // Unique identifier: "node-1", "node-2", etc.
    private final String host;          // IP or hostname
    private final int port;             // Service port
    private volatile NodeStatus status; // Current health status
    private final long addedAt;         // When this node joined the cluster

    public CacheNode(String nodeId, String host, int port) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.status = NodeStatus.UP;
        this.addedAt = System.currentTimeMillis();
    }

    /**
     * Convenience constructor for testing: auto-generates host:port from nodeId.
     */
    public CacheNode(String nodeId) {
        this(nodeId, "127.0.0.1", 6379 + Math.abs(nodeId.hashCode() % 1000));
    }

    /**
     * Returns a deterministic identifier used for hashing.
     * Format: "nodeId:host:port" -- ensures uniqueness even with same host, different port.
     */
    public String getHashKey() {
        return nodeId + ":" + host + ":" + port;
    }

    public boolean isAvailable() {
        return status == NodeStatus.UP;
    }

    // --- Getters and setters ---
    public String getNodeId()       { return nodeId; }
    public String getHost()         { return host; }
    public int getPort()            { return port; }
    public NodeStatus getStatus()   { return status; }
    public long getAddedAt()        { return addedAt; }

    public void setStatus(NodeStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return String.format("CacheNode[id=%s, %s:%d, status=%s]",
                nodeId, host, port, status);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheNode that = (CacheNode) o;
        return nodeId.equals(that.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }
}
```

---

### 4.3 VirtualNode (Consistent Hashing Ring)

> Virtual nodes solve the "hotspot" problem in consistent hashing. Instead of placing one point per physical node on the ring, we place 150 virtual nodes per physical node. This ensures even key distribution even when nodes are added/removed.

```java
/**
 * A virtual node on the consistent hashing ring.
 *
 * WHY virtual nodes?
 * ===================
 * With only N physical nodes on a ring, key distribution can be very uneven.
 * Example with 3 nodes: one node might own 60% of the ring, another 10%.
 *
 * Virtual nodes fix this:
 *   - Each physical node gets 150 virtual nodes (configurable)
 *   - 3 physical nodes = 450 points on the ring
 *   - Statistical distribution becomes much more uniform
 *   - When a node is added/removed, only ~1/N of keys are affected
 *
 * RING VISUALIZATION (3 physical nodes, 3 vnodes each for simplicity):
 *
 *                     0 (top)
 *                   /    \
 *               A-v2      B-v1
 *              /              \
 *           C-v3      RING     A-v1
 *              \              /
 *               B-v3      C-v1
 *                   \    /
 *                    C-v2
 *
 * A key hashes to a point on the ring. Walk clockwise to find the first
 * virtual node. That vnode's physical node owns the key.
 */
public class VirtualNode {
    private final int hash;             // Position on the consistent hashing ring (0 to Integer.MAX_VALUE)
    private final CacheNode physicalNode;  // The real node this vnode maps to
    private final int replicaIndex;     // Which vnode this is: 0, 1, ..., 149

    public VirtualNode(int hash, CacheNode physicalNode, int replicaIndex) {
        Objects.requireNonNull(physicalNode, "physicalNode must not be null");
        this.hash = hash;
        this.physicalNode = physicalNode;
        this.replicaIndex = replicaIndex;
    }

    /**
     * Returns a human-readable label for this virtual node.
     * Used in debugging and stats display.
     * Example: "node-1#42" means physical node "node-1", replica index 42.
     */
    public String getLabel() {
        return physicalNode.getNodeId() + "#" + replicaIndex;
    }

    // --- Getters ---
    public int getHash()                { return hash; }
    public CacheNode getPhysicalNode()  { return physicalNode; }
    public int getReplicaIndex()        { return replicaIndex; }

    @Override
    public String toString() {
        return String.format("VirtualNode[hash=%d, node=%s, replica=%d]",
                hash, physicalNode.getNodeId(), replicaIndex);
    }
}
```

---

### 4.4 CacheConfig (Configuration)

```java
/**
 * Immutable configuration for the cache system.
 * Built once at startup by AppConfig, passed to all components.
 *
 * WHY a separate config object?
 *   - Avoids scattering magic numbers across classes
 *   - Easy to swap configurations (test vs production)
 *   - Single source of truth for all tunable parameters
 */
public class CacheConfig {
    private final int maxEntriesPerNode;     // Max cache entries per node (e.g., 10000)
    private final String evictionPolicy;     // "LRU", "LFU", or "TTL"
    private final long defaultTtlMillis;     // Default TTL for entries without explicit TTL
    private final int virtualNodeCount;      // Virtual nodes per physical node (default: 150)
    private final int replicaCount;          // Number of replica copies (default: 2)
    private final long cleanupIntervalMillis;// Interval for TTL cleanup thread (default: 60000)

    private CacheConfig(Builder builder) {
        this.maxEntriesPerNode = builder.maxEntriesPerNode;
        this.evictionPolicy = builder.evictionPolicy;
        this.defaultTtlMillis = builder.defaultTtlMillis;
        this.virtualNodeCount = builder.virtualNodeCount;
        this.replicaCount = builder.replicaCount;
        this.cleanupIntervalMillis = builder.cleanupIntervalMillis;
    }

    // --- Getters ---
    public int getMaxEntriesPerNode()      { return maxEntriesPerNode; }
    public String getEvictionPolicy()      { return evictionPolicy; }
    public long getDefaultTtlMillis()      { return defaultTtlMillis; }
    public int getVirtualNodeCount()       { return virtualNodeCount; }
    public int getReplicaCount()           { return replicaCount; }
    public long getCleanupIntervalMillis() { return cleanupIntervalMillis; }

    @Override
    public String toString() {
        return String.format(
            "CacheConfig[maxEntries=%d, eviction=%s, ttl=%dms, vnodes=%d, replicas=%d]",
            maxEntriesPerNode, evictionPolicy, defaultTtlMillis, virtualNodeCount, replicaCount);
    }

    // === Builder ===
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxEntriesPerNode = 10_000;
        private String evictionPolicy = "LRU";
        private long defaultTtlMillis = 300_000;    // 5 minutes
        private int virtualNodeCount = 150;
        private int replicaCount = 2;
        private long cleanupIntervalMillis = 60_000; // 1 minute

        public Builder maxEntriesPerNode(int val)      { this.maxEntriesPerNode = val;     return this; }
        public Builder evictionPolicy(String val)      { this.evictionPolicy = val;        return this; }
        public Builder defaultTtlMillis(long val)      { this.defaultTtlMillis = val;      return this; }
        public Builder virtualNodeCount(int val)       { this.virtualNodeCount = val;      return this; }
        public Builder replicaCount(int val)           { this.replicaCount = val;          return this; }
        public Builder cleanupIntervalMillis(long val) { this.cleanupIntervalMillis = val; return this; }

        public CacheConfig build() {
            if (maxEntriesPerNode <= 0) throw new IllegalArgumentException("maxEntriesPerNode must be > 0");
            if (virtualNodeCount <= 0) throw new IllegalArgumentException("virtualNodeCount must be > 0");
            return new CacheConfig(this);
        }
    }
}
```

**Interview point**: `CacheConfig` is immutable (no setters, fields are final, set only in constructor). This means once created, it can be safely shared across threads with zero synchronization.

---

## 5. Interface Contracts

> These interfaces define the contracts that strategy implementations must honor. Each is small, focused (ISP), and enables the Open-Closed Principle: new implementations can be added without touching existing code.

### 5.1 EvictionStrategy (Core Eviction Contract)

```java
/**
 * Strategy interface for cache eviction policies.
 *
 * The eviction strategy tracks access patterns and decides WHICH key to evict
 * when the cache is full. Different implementations optimize for different
 * access patterns:
 *
 *   - LRU: best for temporal locality (recently used data is likely needed again)
 *   - LFU: best for frequency skew (popular items stay, rare items go)
 *   - TTL: best for time-sensitive data (sessions, tokens, rate-limit counters)
 *
 * CONTRACT:
 *   - onGet(key) MUST be called on every cache read  (updates access metadata)
 *   - onPut(key) MUST be called on every cache write (registers new entry)
 *   - evict()    MUST return the key to evict (based on policy), or empty
 *   - remove(key) MUST be called when a key is explicitly deleted
 *
 * THREAD SAFETY: Implementations must be thread-safe. CacheService calls
 * these methods from multiple request threads concurrently.
 *
 * @param <K> the key type
 */
public interface EvictionStrategy<K> {

    /**
     * Notifies the strategy that a key was accessed (cache GET hit).
     * LRU: moves key to most-recently-used position
     * LFU: increments access frequency
     * TTL: refreshes last-access time (for idle TTL variants)
     */
    void onGet(K key);

    /**
     * Notifies the strategy that a new key was inserted (cache PUT).
     * LRU: adds key as most-recently-used
     * LFU: initializes key at frequency 1
     * TTL: records insertion time + TTL deadline
     */
    void onPut(K key);

    /**
     * Selects and removes the best eviction candidate.
     * LRU: evicts least-recently-used (tail of linked list)
     * LFU: evicts least-frequently-used (from min-frequency bucket)
     * TTL: evicts earliest-expiring entry
     *
     * @return the evicted key, or empty if nothing to evict
     */
    Optional<K> evict();

    /**
     * Removes a key from the strategy's tracking data structures.
     * Called when a key is explicitly deleted (not evicted).
     */
    void remove(K key);

    /** Number of keys currently tracked by this strategy. */
    int size();

    /** Clears all tracking data. */
    void clear();

    /** Human-readable name for logging and stats. */
    String getStrategyName();
}
```

---

### 5.2 HashingStrategy (Node Routing Contract)

```java
/**
 * Strategy interface for distributing keys across cache nodes.
 *
 * Two implementations demonstrate the difference between naive and
 * production-grade approaches:
 *
 *   - ModHashStrategy: key.hashCode() % nodeCount
 *     PROBLEM: adding/removing a node remaps ~ALL keys (cache stampede)
 *
 *   - ConsistentHashStrategy: virtual nodes on a hash ring
 *     BENEFIT: adding/removing a node remaps only ~1/N keys
 *
 * This interface abstracts the routing so CacheService and NodeAwareCacheStore
 * do not know (or care) which algorithm is used.
 *
 * CONTRACT:
 *   - getNode(key) MUST return a deterministic node for the same key
 *     (same key always routes to same node, unless topology changes)
 *   - addNode/removeNode MUST update the routing state atomically
 */
public interface HashingStrategy {

    /**
     * Determines which cache node should store the given key.
     *
     * @param key the cache key
     * @return the target node, or empty if no nodes are available
     */
    Optional<CacheNode> getNode(String key);

    /**
     * Adds a new physical node to the hashing topology.
     * For consistent hashing: places virtual nodes on the ring.
     * For mod hashing: appends to the node list (invalidates all mappings).
     *
     * @param node the node to add
     */
    void addNode(CacheNode node);

    /**
     * Removes a physical node from the hashing topology.
     * Returns the set of keys that were mapped to this node
     * (these keys need to be remapped to other nodes).
     *
     * @param node the node to remove
     * @return keys that were owned by this node (need redistribution)
     */
    Set<String> removeNode(CacheNode node);

    /** Returns the successor nodes after the given key on the ring (for replication). */
    List<CacheNode> getSuccessorNodes(String key, int count);

    /** Total number of physical nodes in the topology. */
    int getNodeCount();

    /** Returns all registered physical nodes. */
    List<CacheNode> getAllNodes();
}
```

---

### 5.3 CacheStore (Storage Contract)

```java
/**
 * Interface for the underlying cache storage engine.
 *
 * Implementations:
 *   - InMemoryCacheStore: single-node, ConcurrentHashMap-backed
 *   - NodeAwareCacheStore: multi-node, routes to correct node via HashingStrategy
 *
 * The CacheService (Facade) depends on this interface, not on concrete stores.
 * This allows swapping storage engines without changing business logic.
 *
 * @param <K> key type
 * @param <V> value type
 */
public interface CacheStore<K, V> {

    /** Retrieves an entry. Returns empty if key not found or entry expired. */
    Optional<CacheEntry<K, V>> get(K key);

    /** Stores an entry. Overwrites if key already exists. */
    void put(K key, CacheEntry<K, V> entry);

    /** Deletes an entry. Returns true if the key existed, false otherwise. */
    boolean delete(K key);

    /** Returns true if the key exists and is not expired. */
    boolean contains(K key);

    /** Number of entries currently stored. */
    int size();

    /** Removes all entries. */
    void clear();

    /** Returns all keys currently in the store. */
    Set<K> keys();
}
```

---

### 5.4 CacheRepository (Backing Store Contract)

```java
/**
 * Interface for the backing store behind the cache (cache-aside pattern).
 *
 * In production:
 *   Cache MISS --> loadFromBacking() --> populate cache --> return to caller
 *   Cache PUT  --> writeToBacking()  --> ensures durability
 *
 * For this LLD, the InMemoryCacheRepository simulates a database.
 * In a real system, this would be JDBC, JPA, or an external API client.
 *
 * @param <K> key type
 * @param <V> value type
 */
public interface CacheRepository<K, V> {

    /** Loads a value from the backing store (e.g., database). */
    Optional<V> loadFromBacking(K key);

    /** Writes a value to the backing store. */
    void writeToBacking(K key, V value);

    /** Deletes a value from the backing store. */
    boolean deleteFromBacking(K key);

    /** Checks if a key exists in the backing store. */
    boolean existsInBacking(K key);
}
```

---

## 6. Strategy Implementations

> This is the heart of the distributed cache LLD. The eviction strategies demonstrate advanced data structures (DoublyLinkedList, frequency buckets), and the hashing strategies demonstrate distributed systems fundamentals (consistent hashing vs naive mod).

### 6.0 The Anti-Pattern: Why Strategy Pattern Matters

Before showing the clean implementations, here is what the code looks like WITHOUT the Strategy pattern. This is what an interviewer wants to see you avoid:

```java
/**
 * ANTI-PATTERN: The "ugly if-else" approach.
 *
 * Every time you add a new eviction policy, you must:
 *   1. Add a new else-if branch here
 *   2. Add new else-if branches in EVERY method that touches eviction
 *   3. Risk breaking existing policies when editing shared code
 *   4. Unit testing becomes a nightmare (one giant class to test)
 *
 * This violates:
 *   - OCP (Open-Closed Principle): must modify existing code for new policies
 *   - SRP (Single Responsibility): one class knows LRU, LFU, TTL internals
 *   - DIP (Dependency Inversion): CacheService depends on concrete logic, not abstraction
 */
public class UglyCacheServiceAntiPattern {

    private String evictionPolicy;  // "LRU", "LFU", "TTL"

    // LRU fields
    private LinkedHashMap<String, Object> lruMap;

    // LFU fields
    private Map<String, Integer> frequencyMap;
    private Map<Integer, LinkedHashSet<String>> frequencyBuckets;
    private int minFrequency;

    // TTL fields
    private Map<String, Long> expiryMap;

    public void put(String key, Object value) {
        // === UGLY: switch on policy string in EVERY method ===
        if ("LRU".equals(evictionPolicy)) {
            lruMap.put(key, value);
            // ... LRU-specific logic
        } else if ("LFU".equals(evictionPolicy)) {
            frequencyMap.put(key, 1);
            frequencyBuckets.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);
            minFrequency = 1;
            // ... LFU-specific logic
        } else if ("TTL".equals(evictionPolicy)) {
            expiryMap.put(key, System.currentTimeMillis() + 300_000);
            // ... TTL-specific logic
        }
        // Adding a new policy? Edit EVERY method. Good luck.
    }

    public Object get(String key) {
        // === SAME ugly switch repeated ===
        if ("LRU".equals(evictionPolicy)) {
            // move to front...
        } else if ("LFU".equals(evictionPolicy)) {
            // bump frequency...
        } else if ("TTL".equals(evictionPolicy)) {
            // check expiry...
        }
        return null;
    }

    public void evict() {
        // === AND AGAIN ===
        if ("LRU".equals(evictionPolicy)) {
            // remove oldest...
        } else if ("LFU".equals(evictionPolicy)) {
            // remove from min bucket...
        } else if ("TTL".equals(evictionPolicy)) {
            // remove expired...
        }
    }
    // 5 more methods with the same if-else chain...
}
```

**Now the clean solution**: Each eviction policy is its own class implementing `EvictionStrategy<K>`. CacheService holds an `EvictionStrategy<K>` reference and delegates. Adding a new policy means writing ONE new class. Zero changes to CacheService.

```
ANTI-PATTERN (above):                  CLEAN PATTERN (below):
========================               ========================
CacheService                           CacheService
  ├── if "LRU" → inline LRU logic       └── evictionStrategy: EvictionStrategy
  ├── if "LFU" → inline LFU logic              │
  └── if "TTL" → inline TTL logic              ├── LRUEvictionStrategy
  (modify here for every new policy)           ├── LFUEvictionStrategy
                                               ├── TTLEvictionStrategy
                                               └── (new policy? just add a class)
```

---

### 6.1 LRUEvictionStrategy (HashMap + DoublyLinkedList, O(1))

> **THE classic data structures interview question within the cache question.** Interviewers expect you to implement LRU from scratch, not use `LinkedHashMap`. Show the DoublyLinkedList internals.

#### Internal Data Structure: DoublyLinkedList Node

```
     ┌──────────────────────────────────────────────────────┐
     │                    LRU Internals                      │
     │                                                       │
     │   HashMap<K, DLLNode<K>>     DoublyLinkedList          │
     │   ┌─────────┬────────┐      HEAD ←→ ... ←→ TAIL      │
     │   │ key     │ *node ─┼──→   (MRU)          (LRU)     │
     │   │ "user1" │ *node ─┼──→                             │
     │   │ "user2" │ *node ─┼──→                             │
     │   └─────────┴────────┘                                │
     │                                                       │
     │   GET "user1":                                        │
     │     1. map.get("user1") → DLLNode          O(1)      │
     │     2. Remove node from current position    O(1)      │
     │     3. Insert node right after HEAD         O(1)      │
     │                                                       │
     │   PUT "user3" (new):                                  │
     │     1. Create new DLLNode("user3")          O(1)      │
     │     2. Insert after HEAD                    O(1)      │
     │     3. map.put("user3", node)               O(1)      │
     │     4. If over capacity → evict TAIL        O(1)      │
     │                                                       │
     │   EVICT:                                              │
     │     1. Remove TAIL.prev (least recent)      O(1)      │
     │     2. map.remove(evicted.key)              O(1)      │
     │                                                       │
     │   ALL OPERATIONS ARE O(1) ← THIS IS THE KEY INSIGHT   │
     └──────────────────────────────────────────────────────┘
```

#### DoublyLinkedList Visualization

```
   Before GET "B":

     HEAD ←→ [D] ←→ [C] ←→ [B] ←→ [A] ←→ TAIL
     (sentinel)  most recent ──────→ least recent  (sentinel)

   After GET "B" (move B to front):

     HEAD ←→ [B] ←→ [D] ←→ [C] ←→ [A] ←→ TAIL
              ↑ moved to front

   Evict (remove from tail):

     HEAD ←→ [B] ←→ [D] ←→ [C] ←→ TAIL
                                      ↑ [A] removed
```

```java
/**
 * LRU (Least Recently Used) eviction strategy.
 *
 * Implementation: HashMap + custom DoublyLinkedList
 *   - HashMap<K, DLLNode<K>> for O(1) key lookup
 *   - DoublyLinkedList for O(1) move-to-front and remove-from-tail
 *
 * WHY NOT just use LinkedHashMap?
 *   - Interview answer: "I know LinkedHashMap(capacity, 0.75f, true) does LRU,
 *     but let me show you the underlying data structure."
 *   - LinkedHashMap is NOT thread-safe (we need explicit synchronization anyway)
 *   - Custom DLL gives us control over node references for O(1) operations
 *
 * Time complexity: ALL operations O(1)
 *   - onGet:  map.get + DLL moveToFront  = O(1)
 *   - onPut:  map.put + DLL addFirst     = O(1)
 *   - evict:  DLL removeLast + map.remove = O(1)
 *   - remove: map.get + DLL removeNode   = O(1)
 *
 * Space complexity: O(n) for n cached keys
 *
 * @param <K> the key type
 */
public class LRUEvictionStrategy<K> implements EvictionStrategy<K> {

    // --- Inner class: Doubly Linked List Node ---
    private static class DLLNode<K> {
        K key;
        DLLNode<K> prev;
        DLLNode<K> next;

        DLLNode(K key) {
            this.key = key;
        }

        /** Sentinel node (head/tail dummy) constructor */
        DLLNode() {
            this(null);
        }
    }

    private final Map<K, DLLNode<K>> map;       // Key → Node (for O(1) lookup)
    private final DLLNode<K> head;               // Sentinel: head.next = most recently used
    private final DLLNode<K> tail;               // Sentinel: tail.prev = least recently used
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();

    public LRUEvictionStrategy(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.map = new HashMap<>(capacity);

        // Initialize sentinel nodes (simplifies edge cases: no null checks)
        this.head = new DLLNode<>();
        this.tail = new DLLNode<>();
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Cache GET hit: move the accessed key to the front (most recently used).
     *
     * Call chain: CacheService.get() → EvictionService.recordAccess() → this.onGet()
     */
    @Override
    public void onGet(K key) {
        lock.lock();
        try {
            DLLNode<K> node = map.get(key);
            if (node != null) {
                removeNode(node);
                addFirst(node);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cache PUT: add the new key at the front (most recently used).
     * If key already exists, move it to front (update case).
     *
     * Call chain: CacheService.put() → EvictionService.recordInsertion() → this.onPut()
     */
    @Override
    public void onPut(K key) {
        lock.lock();
        try {
            if (map.containsKey(key)) {
                // Key already tracked: move to front
                DLLNode<K> existing = map.get(key);
                removeNode(existing);
                addFirst(existing);
            } else {
                // New key: create node, add to front
                DLLNode<K> node = new DLLNode<>(key);
                map.put(key, node);
                addFirst(node);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evicts the LEAST recently used key (tail of list).
     *
     * Call chain: CacheService.put() → EvictionService.evictIfNeeded() → this.evict()
     *
     * @return the evicted key, or empty if the strategy is tracking no keys
     */
    @Override
    public Optional<K> evict() {
        lock.lock();
        try {
            if (map.isEmpty()) {
                return Optional.empty();
            }
            // The least recently used key is at tail.prev
            DLLNode<K> lruNode = tail.prev;
            removeNode(lruNode);
            map.remove(lruNode.key);
            return Optional.of(lruNode.key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void remove(K key) {
        lock.lock();
        try {
            DLLNode<K> node = map.remove(key);
            if (node != null) {
                removeNode(node);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String getStrategyName() {
        return "LRU";
    }

    // === Private DLL operations (all O(1)) ===

    /** Inserts node right after HEAD (most recently used position). */
    private void addFirst(DLLNode<K> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    /** Removes node from its current position in the list. */
    private void removeNode(DLLNode<K> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }
}
```

**Why sentinel nodes?** Without sentinels, every `addFirst` and `removeLast` needs null checks for empty list, single-element list, etc. Sentinels eliminate ALL edge cases. `head.next` is always the first real node, `tail.prev` is always the last.

---

### 6.2 LFUEvictionStrategy (HashMap + Frequency Buckets, O(1))

> **The hardest eviction policy to implement in O(1).** The key insight is the frequency bucket map: instead of a priority queue (O(log n)), use a `Map<Integer, LinkedHashSet<K>>` where each bucket holds keys with the same frequency. Track `minFrequency` to evict in O(1).

#### Internal Data Structure

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                       LFU Internals                              │
     │                                                                  │
     │   keyToNode: HashMap<K, LFUNode>                                 │
     │   ┌───────────┬──────────────────────┐                           │
     │   │ key       │ LFUNode(key,freq)    │                           │
     │   │ "user1"   │ LFUNode("user1", 3)  │                          │
     │   │ "user2"   │ LFUNode("user2", 1)  │                          │
     │   │ "user3"   │ LFUNode("user3", 1)  │                          │
     │   │ "user4"   │ LFUNode("user4", 5)  │                          │
     │   └───────────┴──────────────────────┘                           │
     │                                                                  │
     │   freqToBucket: HashMap<Integer, LinkedHashSet<K>>               │
     │   ┌──────┬────────────────────────┐                              │
     │   │ freq │ keys (insertion order) │                              │
     │   │  1   │ { "user2", "user3" }   │  ← minFrequency = 1        │
     │   │  3   │ { "user1" }            │                              │
     │   │  5   │ { "user4" }            │                              │
     │   └──────┴────────────────────────┘                              │
     │                                                                  │
     │   minFrequency = 1                                               │
     │                                                                  │
     │   EVICT:                                                         │
     │     1. Get bucket at minFrequency (freq=1)            O(1)      │
     │     2. Remove FIRST element ("user2" -- oldest at freq 1) O(1)  │
     │     3. If bucket is now empty, minFreq stays or adjusts   O(1)  │
     │                                                                  │
     │   GET "user2" (freq 1 → 2):                                     │
     │     1. keyToNode.get("user2") → LFUNode(freq=1)      O(1)      │
     │     2. Remove "user2" from freqBucket[1]              O(1)      │
     │     3. Add "user2" to freqBucket[2]                   O(1)      │
     │     4. Update node.freq = 2                           O(1)      │
     │     5. If freqBucket[1] is empty AND minFreq==1       O(1)      │
     │        → minFreq = 2                                            │
     │                                                                  │
     │   PUT "user5" (new key, freq=1):                                │
     │     1. Create LFUNode("user5", freq=1)                O(1)      │
     │     2. Add "user5" to freqBucket[1]                   O(1)      │
     │     3. minFrequency = 1 (new key always starts at 1)  O(1)      │
     │                                                                  │
     │   ALL OPERATIONS ARE O(1) ← same as LRU but trickier            │
     └──────────────────────────────────────────────────────────────────┘
```

#### Tie-Breaking Visualization

```
   When multiple keys have the same minimum frequency, which one to evict?
   Answer: the OLDEST one at that frequency (FIFO within the bucket).

   LinkedHashSet maintains insertion order, so iterator().next() gives the oldest.

   freq=1 bucket: { "user2", "user3" }
                     ↑ evicted first (inserted earlier)

   This is why we use LinkedHashSet, not plain HashSet.
```

```java
/**
 * LFU (Least Frequently Used) eviction strategy.
 *
 * Implementation: Two HashMaps + LinkedHashSet buckets
 *   - keyToNode: HashMap<K, LFUNode<K>> for O(1) key → frequency lookup
 *   - freqToBucket: HashMap<Integer, LinkedHashSet<K>> for O(1) eviction
 *   - minFrequency: int tracking the current minimum frequency
 *
 * The LinkedHashSet is critical: when two keys have the same frequency,
 * we evict the one that reached that frequency FIRST (FIFO tie-breaking).
 * LinkedHashSet preserves insertion order, so iterator().next() gives the oldest.
 *
 * Time complexity: ALL operations O(1)
 *   - onGet:  lookup + bucket move + minFreq update   = O(1)
 *   - onPut:  insert into freq=1 bucket + set minFreq = O(1)
 *   - evict:  get minFreq bucket + remove first        = O(1)
 *   - remove: lookup + bucket remove                   = O(1)
 *
 * Space complexity: O(n) for n cached keys
 *
 * @param <K> the key type
 */
public class LFUEvictionStrategy<K> implements EvictionStrategy<K> {

    // --- Inner class: tracks key and its current frequency ---
    private static class LFUNode<K> {
        K key;
        int frequency;

        LFUNode(K key) {
            this.key = key;
            this.frequency = 1;
        }
    }

    private final Map<K, LFUNode<K>> keyToNode;                    // Key → metadata
    private final Map<Integer, LinkedHashSet<K>> freqToBucket;     // Frequency → keys at that frequency
    private int minFrequency;                                       // Current minimum frequency
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();

    public LFUEvictionStrategy(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.keyToNode = new HashMap<>(capacity);
        this.freqToBucket = new HashMap<>();
        this.minFrequency = 0;
    }

    /**
     * Cache GET hit: increment frequency, move key to new frequency bucket.
     *
     * Steps:
     *   1. Look up current frequency (oldFreq)
     *   2. Remove key from freqBucket[oldFreq]
     *   3. If freqBucket[oldFreq] is now empty AND oldFreq == minFrequency
     *      → increment minFrequency
     *   4. Add key to freqBucket[oldFreq + 1]
     *   5. Update node's frequency
     */
    @Override
    public void onGet(K key) {
        lock.lock();
        try {
            LFUNode<K> node = keyToNode.get(key);
            if (node == null) return;

            int oldFreq = node.frequency;
            int newFreq = oldFreq + 1;

            // Remove from old frequency bucket
            LinkedHashSet<K> oldBucket = freqToBucket.get(oldFreq);
            if (oldBucket != null) {
                oldBucket.remove(key);
                // If the old bucket is empty AND it was the min frequency → bump min
                if (oldBucket.isEmpty() && oldFreq == minFrequency) {
                    minFrequency = newFreq;
                }
            }

            // Add to new frequency bucket
            freqToBucket.computeIfAbsent(newFreq, k -> new LinkedHashSet<>()).add(key);
            node.frequency = newFreq;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cache PUT: add new key at frequency 1.
     * New keys ALWAYS start at frequency 1, so minFrequency is reset to 1.
     *
     * If the key already exists, treat it like onGet (frequency bump).
     */
    @Override
    public void onPut(K key) {
        lock.lock();
        try {
            if (keyToNode.containsKey(key)) {
                // Key already tracked: bump frequency (same as onGet)
                onGet(key);
                return;
            }

            // New key: create node at frequency 1
            LFUNode<K> node = new LFUNode<>(key);
            keyToNode.put(key, node);
            freqToBucket.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);

            // New key starts at freq 1, which is always the new minimum
            minFrequency = 1;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evicts the least frequently used key.
     * If tie (multiple keys at minFrequency), evicts the OLDEST one (FIFO).
     *
     * Steps:
     *   1. Get the bucket at minFrequency
     *   2. Remove the FIRST element (oldest at this frequency, thanks to LinkedHashSet)
     *   3. Remove from keyToNode map
     */
    @Override
    public Optional<K> evict() {
        lock.lock();
        try {
            if (keyToNode.isEmpty()) {
                return Optional.empty();
            }

            // Get the bucket with the minimum frequency
            LinkedHashSet<K> minBucket = freqToBucket.get(minFrequency);
            if (minBucket == null || minBucket.isEmpty()) {
                return Optional.empty();
            }

            // Remove the FIRST element (oldest at min frequency) -- O(1)
            K evictedKey = minBucket.iterator().next();
            minBucket.remove(evictedKey);
            keyToNode.remove(evictedKey);

            return Optional.of(evictedKey);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void remove(K key) {
        lock.lock();
        try {
            LFUNode<K> node = keyToNode.remove(key);
            if (node != null) {
                LinkedHashSet<K> bucket = freqToBucket.get(node.frequency);
                if (bucket != null) {
                    bucket.remove(key);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return keyToNode.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            keyToNode.clear();
            freqToBucket.clear();
            minFrequency = 0;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String getStrategyName() {
        return "LFU";
    }
}
```

**Interview follow-up**: "Why LinkedHashSet and not TreeSet?" Answer: TreeSet gives O(log n) operations. LinkedHashSet gives O(1) amortized for add/remove/iterator.next(). Since we only need insertion-order FIFO for tie-breaking, LinkedHashSet is optimal.

---

### 6.3 TTLEvictionStrategy (Lazy + Active Expiration)

> **Real-world caching concept**: Redis uses this exact approach. Lazy expiration checks on access (no wasted CPU). Active expiration runs periodically to clean up entries that are never accessed again (prevents memory leaks).

#### Expiration Model

```
     ┌───────────────────────────────────────────────────────────────┐
     │                    TTL Expiration Model                       │
     │                                                               │
     │   Two complementary approaches (Redis-style):                 │
     │                                                               │
     │   1. LAZY EXPIRATION (on access):                             │
     │      When a GET request arrives for key "X":                  │
     │        → Check if entry.isExpired()                           │
     │        → If expired: delete it, return cache MISS             │
     │        → If not expired: return the value                     │
     │                                                               │
     │      PRO: Zero CPU overhead for keys that are never accessed  │
     │      CON: Expired but unaccessed keys leak memory             │
     │                                                               │
     │   2. ACTIVE EXPIRATION (background thread):                   │
     │      A daemon thread wakes up every N seconds:                │
     │        → Scans a sample of keys                               │
     │        → Deletes any that have expired                        │
     │        → If >25% of sample was expired, scan again            │
     │                                                               │
     │      PRO: Prevents memory leaks from unaccessed expired keys  │
     │      CON: Small CPU overhead for the background scan          │
     │                                                               │
     │   Together: lazy handles the hot path, active handles cleanup │
     └───────────────────────────────────────────────────────────────┘

     Timeline:
     ┌─────────────────────────────────────────────────────────────┐
     │   PUT key="session-123" (TTL=300s)                          │
     │   |                                                         │
     │   t=0s        t=200s            t=300s         t=360s       │
     │   |           |                 |              |            │
     │   ├───────────┼─────────────────┼──────────────┤            │
     │   PUT         GET (valid,       EXPIRED        Active       │
     │               resets nothing    |              cleanup      │
     │               for TTL)         If GET here:    deletes it   │
     │                                lazy delete                  │
     │                                (return miss)                │
     └─────────────────────────────────────────────────────────────┘
```

```java
/**
 * TTL (Time-To-Live) eviction strategy.
 *
 * Uses a PriorityQueue (min-heap) ordered by expiry time to efficiently
 * find the next-to-expire entry. Combined with lazy expiration on access.
 *
 * Two modes:
 *   1. Lazy: on every onGet(), check if the entry is expired
 *   2. Active: a background cleanup thread periodically removes expired entries
 *
 * Time complexity:
 *   - onGet:  O(1) for expiry check
 *   - onPut:  O(log n) for PriorityQueue insertion
 *   - evict:  O(log n) for PriorityQueue poll (but amortized O(1) in batch cleanup)
 *   - remove: O(n) for PriorityQueue removal (acceptable -- explicit deletes are rare)
 *
 * @param <K> the key type
 */
public class TTLEvictionStrategy<K> implements EvictionStrategy<K> {

    // --- Inner class: tracks key and its absolute expiry time ---
    private record ExpiryEntry<K>(K key, long expiryTimeMillis) implements Comparable<ExpiryEntry<K>> {
        @Override
        public int compareTo(ExpiryEntry<K> other) {
            return Long.compare(this.expiryTimeMillis, other.expiryTimeMillis);
        }
    }

    private final Map<K, Long> keyToExpiry;                  // Key → expiry time (for O(1) lookup)
    private final PriorityQueue<ExpiryEntry<K>> expiryQueue; // Min-heap by expiry time
    private final long defaultTtlMillis;
    private final ReentrantLock lock = new ReentrantLock();

    // Active cleanup thread
    private final ScheduledExecutorService cleanupExecutor;
    private final long cleanupIntervalMillis;

    // Callback for notifying CacheStore to actually delete the entry
    private volatile Consumer<K> onExpireCallback;

    public TTLEvictionStrategy(long defaultTtlMillis, long cleanupIntervalMillis) {
        this.defaultTtlMillis = defaultTtlMillis;
        this.cleanupIntervalMillis = cleanupIntervalMillis;
        this.keyToExpiry = new HashMap<>();
        this.expiryQueue = new PriorityQueue<>();
        this.onExpireCallback = key -> {};  // No-op default

        // Start active cleanup daemon thread
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ttl-cleanup-daemon");
            t.setDaemon(true);  // Won't prevent JVM shutdown
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(
            this::activeCleanup,
            cleanupIntervalMillis,
            cleanupIntervalMillis,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Sets the callback invoked when a key expires.
     * CacheStore registers this to delete the actual cached value.
     *
     * Wiring: AppConfig sets this after constructing the strategy and store:
     *   ttlStrategy.setOnExpireCallback(key -> cacheStore.delete(key));
     */
    public void setOnExpireCallback(Consumer<K> callback) {
        this.onExpireCallback = callback;
    }

    /**
     * Lazy expiration: checks if the accessed key has expired.
     * Returns without action if not expired.
     * If expired, removes it and triggers the callback.
     */
    @Override
    public void onGet(K key) {
        lock.lock();
        try {
            Long expiry = keyToExpiry.get(key);
            if (expiry != null && System.currentTimeMillis() > expiry) {
                // Expired: remove and notify
                keyToExpiry.remove(key);
                onExpireCallback.accept(key);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a new key with its TTL deadline.
     */
    @Override
    public void onPut(K key) {
        lock.lock();
        try {
            long expiryTime = System.currentTimeMillis() + defaultTtlMillis;
            keyToExpiry.put(key, expiryTime);
            expiryQueue.offer(new ExpiryEntry<>(key, expiryTime));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evicts the entry with the earliest expiry time.
     * Skips entries that have already been removed (stale queue entries).
     */
    @Override
    public Optional<K> evict() {
        lock.lock();
        try {
            while (!expiryQueue.isEmpty()) {
                ExpiryEntry<K> entry = expiryQueue.poll();
                // Check if this entry is still valid (not already removed)
                Long currentExpiry = keyToExpiry.get(entry.key());
                if (currentExpiry != null && currentExpiry.equals(entry.expiryTimeMillis())) {
                    keyToExpiry.remove(entry.key());
                    return Optional.of(entry.key());
                }
                // Stale entry: skip and try next
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void remove(K key) {
        lock.lock();
        try {
            keyToExpiry.remove(key);
            // Note: we do NOT remove from PriorityQueue (O(n) removal).
            // Instead, evict() skips stale entries. This is the lazy-deletion
            // approach -- same as what Redis does internally.
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return keyToExpiry.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            keyToExpiry.clear();
            expiryQueue.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String getStrategyName() {
        return "TTL";
    }

    /**
     * Active cleanup: scans the PriorityQueue head for expired entries.
     * Runs on a background daemon thread every cleanupIntervalMillis.
     *
     * Algorithm (inspired by Redis):
     *   - Peek at the queue head (earliest expiry)
     *   - If expired, remove it and notify via callback
     *   - Repeat until head is not expired or queue is empty
     */
    private void activeCleanup() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            int cleaned = 0;

            while (!expiryQueue.isEmpty()) {
                ExpiryEntry<K> head = expiryQueue.peek();
                if (head.expiryTimeMillis() > now) {
                    break;  // Head not expired → nothing else is either (min-heap)
                }

                expiryQueue.poll();
                Long currentExpiry = keyToExpiry.get(head.key());
                if (currentExpiry != null && currentExpiry <= now) {
                    keyToExpiry.remove(head.key());
                    onExpireCallback.accept(head.key());
                    cleaned++;
                }
            }

            if (cleaned > 0) {
                System.out.printf("[TTL-CLEANUP] Removed %d expired entries%n", cleaned);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Shuts down the cleanup thread. Call on cache shutdown. */
    public void shutdown() {
        cleanupExecutor.shutdown();
    }
}
```

---

### 6.4 ConsistentHashStrategy (TreeMap + Virtual Nodes, MD5)

> **THE distributed systems question.** Interviewers expect you to explain why mod hashing is bad, how consistent hashing fixes it, and what virtual nodes add.

#### Why Not Mod Hashing? (The Problem)

```
     MOD HASHING: node = key.hashCode() % numberOfNodes

     3 nodes:  key "user1" → hash 7 → 7 % 3 = node 1
               key "user2" → hash 4 → 4 % 3 = node 1
               key "user3" → hash 9 → 9 % 3 = node 0

     Add a 4th node (scale out):
               key "user1" → hash 7 → 7 % 4 = node 3  ← CHANGED! Was node 1
               key "user2" → hash 4 → 4 % 4 = node 0  ← CHANGED! Was node 1
               key "user3" → hash 9 → 9 % 4 = node 1  ← CHANGED! Was node 0

     RESULT: ~75% of ALL keys are remapped → cache stampede → thundering herd to DB
```

#### How Consistent Hashing Fixes It

```
     CONSISTENT HASHING: keys and nodes are on the same ring [0, 2^32)

     Ring with 3 physical nodes (each has virtual nodes, simplified here):

                        0
                      /   \
                   A         B
                  /             \
                 |    ┌─────┐    |
                 |    │RING │    |
                  \   └─────┘  /
                   C         
                    \       /
                      ─────

     Key "user1" hashes to position X on the ring.
     Walk CLOCKWISE until you hit the first node → that node owns the key.

     Add node D between B and C:

                        0
                      /   \
                   A         B
                  /             \
                 |    ┌─────┐    |
                 |    │RING │   D  ← new node
                  \   └─────┘  /
                   C         
                    \       /
                      ─────

     Only keys between B and D are remapped (from C to D).
     Keys between A and B: unchanged.
     Keys between C and A: unchanged.

     RESULT: only ~1/N of keys are remapped (not ~75% like mod hashing)
```

#### Virtual Nodes: Even Distribution

```
     Problem with just 3 physical points on the ring:
     Node A might own 60% of the ring, node C only 10%.

     Solution: place 150 virtual nodes per physical node:
     A gets: A#0, A#1, A#2, ..., A#149 (150 points scattered on ring)
     B gets: B#0, B#1, B#2, ..., B#149
     C gets: C#0, C#1, C#2, ..., C#149

     Total: 450 points on the ring → much more uniform distribution.

     Statistical proof: with V virtual nodes per physical node and N physical nodes:
     Each physical node owns ~1/N of the ring, with standard deviation ~1/(N*sqrt(V))
     With N=3, V=150: each node owns ~33.3% ± 2.7% of keys → acceptable.
```

```java
/**
 * Consistent hashing with virtual nodes.
 *
 * Implementation:
 *   - TreeMap<Integer, VirtualNode> as the hash ring
 *   - TreeMap.ceilingEntry() for O(log V*N) clockwise lookup
 *   - 150 virtual nodes per physical node (configurable)
 *   - MD5 hashing for uniform distribution on the ring
 *
 * WHY TreeMap?
 *   - We need "find the first entry >= hash" → TreeMap.ceilingEntry() does this in O(log n)
 *   - We need "if nothing >= hash, wrap around to first entry" → TreeMap.firstEntry()
 *   - TreeMap is a Red-Black Tree: sorted, balanced, O(log n) operations
 *
 * WHY MD5 (not hashCode())?
 *   - Java's hashCode() has poor distribution for strings like "node-1#0", "node-1#1"
 *   - MD5 spreads sequential inputs uniformly across the hash space
 *   - We only use 4 bytes of MD5 (32-bit int) -- not for security, just distribution
 *
 * Time complexity:
 *   - getNode:     O(log(V*N)) where V=vnodes, N=physical nodes
 *   - addNode:     O(V * log(V*N)) -- insert V virtual nodes into TreeMap
 *   - removeNode:  O(V * log(V*N)) -- remove V virtual nodes from TreeMap
 */
public class ConsistentHashStrategy implements HashingStrategy {

    private final TreeMap<Integer, VirtualNode> ring;    // Hash → VirtualNode
    private final Map<String, CacheNode> nodeMap;        // nodeId → CacheNode
    private final int virtualNodeCount;                   // Vnodes per physical node (default: 150)
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    public ConsistentHashStrategy(int virtualNodeCount) {
        this.virtualNodeCount = virtualNodeCount;
        this.ring = new TreeMap<>();
        this.nodeMap = new HashMap<>();
    }

    public ConsistentHashStrategy() {
        this(150);  // Default: 150 virtual nodes per physical node
    }

    /**
     * Finds the node responsible for the given key.
     *
     * Algorithm:
     *   1. Hash the key to a position on the ring
     *   2. Find the first virtual node at or AFTER that position (clockwise)
     *   3. If no node after → wrap around to the FIRST node on the ring
     *   4. Return the physical node that owns that virtual node
     *
     * TreeMap operations used:
     *   - ceilingEntry(hash): first entry >= hash → O(log n)
     *   - firstEntry(): smallest entry (wrap-around) → O(log n)
     */
    @Override
    public Optional<CacheNode> getNode(String key) {
        rwLock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return Optional.empty();
            }

            int hash = hashKey(key);

            // Find the first virtual node at or clockwise from this hash
            Map.Entry<Integer, VirtualNode> entry = ring.ceilingEntry(hash);

            // If nothing found clockwise, wrap around to the start of the ring
            if (entry == null) {
                entry = ring.firstEntry();
            }

            CacheNode node = entry.getValue().getPhysicalNode();

            // Skip unavailable nodes: walk clockwise to find the next UP node
            if (!node.isAvailable()) {
                return findNextAvailableNode(hash);
            }

            return Optional.of(node);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Adds a physical node to the ring by placing virtualNodeCount virtual nodes.
     *
     * Each virtual node's position is determined by:
     *   MD5(nodeId + "#" + replicaIndex)
     * This gives uniform, deterministic distribution.
     */
    @Override
    public void addNode(CacheNode node) {
        rwLock.writeLock().lock();
        try {
            nodeMap.put(node.getNodeId(), node);

            for (int i = 0; i < virtualNodeCount; i++) {
                String virtualKey = node.getNodeId() + "#" + i;
                int hash = hashKey(virtualKey);
                VirtualNode vnode = new VirtualNode(hash, node, i);
                ring.put(hash, vnode);
            }

            System.out.printf("[HASH-RING] Added node %s with %d virtual nodes. Ring size: %d%n",
                    node.getNodeId(), virtualNodeCount, ring.size());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Removes a physical node from the ring.
     * Returns the set of keys that were mapped to this node (need redistribution).
     *
     * In a real system, the caller would:
     *   1. Get affected keys
     *   2. For each key, call getNode() to find the new owner
     *   3. Migrate the key-value pair to the new owner
     */
    @Override
    public Set<String> removeNode(CacheNode node) {
        rwLock.writeLock().lock();
        try {
            Set<String> affectedKeys = new HashSet<>();

            // Remove all virtual nodes for this physical node
            for (int i = 0; i < virtualNodeCount; i++) {
                String virtualKey = node.getNodeId() + "#" + i;
                int hash = hashKey(virtualKey);
                ring.remove(hash);
            }

            nodeMap.remove(node.getNodeId());

            System.out.printf("[HASH-RING] Removed node %s. Ring size: %d%n",
                    node.getNodeId(), ring.size());

            return affectedKeys;  // Caller determines affected keys from their store
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Returns the next N distinct physical nodes clockwise from the key's position.
     * Used by ReplicationService to find replica nodes.
     *
     * Example: key hashes to position X. Successor nodes (walking clockwise):
     *   1st distinct physical node → primary
     *   2nd distinct physical node → replica 1
     *   3rd distinct physical node → replica 2
     *
     * We skip virtual nodes that belong to the same physical node.
     */
    @Override
    public List<CacheNode> getSuccessorNodes(String key, int count) {
        rwLock.readLock().lock();
        try {
            List<CacheNode> successors = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            if (ring.isEmpty()) return successors;

            int hash = hashKey(key);

            // Walk clockwise from the key's position
            // tailMap(hash, false) gives all entries after 'hash'
            for (VirtualNode vnode : ring.tailMap(hash, false).values()) {
                if (addIfNewNode(vnode, seen, successors, count)) return successors;
            }

            // Wrap around: continue from the beginning of the ring
            for (VirtualNode vnode : ring.values()) {
                if (addIfNewNode(vnode, seen, successors, count)) return successors;
            }

            return successors;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public int getNodeCount() {
        rwLock.readLock().lock();
        try {
            return nodeMap.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public List<CacheNode> getAllNodes() {
        rwLock.readLock().lock();
        try {
            return new ArrayList<>(nodeMap.values());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // === Private helpers ===

    /**
     * MD5-based hash function.
     * Returns a 32-bit integer from the first 4 bytes of the MD5 digest.
     *
     * WHY MD5?
     *   - Uniform distribution (critical for consistent hashing)
     *   - Deterministic (same input → same output across JVM restarts)
     *   - Not for security -- just for spreading keys evenly on the ring
     */
    private int hashKey(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            // Use first 4 bytes to construct an int
            return ((digest[0] & 0xFF) << 24)
                 | ((digest[1] & 0xFF) << 16)
                 | ((digest[2] & 0xFF) << 8)
                 | (digest[3] & 0xFF);
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed to be available in all Java implementations
            throw new RuntimeException("MD5 not available", e);
        }
    }

    /** Walks clockwise to find the next available (UP) node. */
    private Optional<CacheNode> findNextAvailableNode(int hash) {
        Set<String> visited = new HashSet<>();

        // Walk clockwise from hash
        for (VirtualNode vnode : ring.tailMap(hash, false).values()) {
            if (visited.add(vnode.getPhysicalNode().getNodeId())
                    && vnode.getPhysicalNode().isAvailable()) {
                return Optional.of(vnode.getPhysicalNode());
            }
        }
        // Wrap around
        for (VirtualNode vnode : ring.values()) {
            if (visited.add(vnode.getPhysicalNode().getNodeId())
                    && vnode.getPhysicalNode().isAvailable()) {
                return Optional.of(vnode.getPhysicalNode());
            }
        }

        return Optional.empty();  // All nodes down
    }

    /** Helper for getSuccessorNodes: adds a node if it's from a new physical node. */
    private boolean addIfNewNode(VirtualNode vnode, Set<String> seen,
                                  List<CacheNode> successors, int count) {
        String nodeId = vnode.getPhysicalNode().getNodeId();
        if (seen.add(nodeId) && vnode.getPhysicalNode().isAvailable()) {
            successors.add(vnode.getPhysicalNode());
        }
        return successors.size() >= count;
    }
}
```

---

### 6.5 ModHashStrategy (Anti-Pattern for Comparison)

```java
/**
 * Naive mod-based hashing: node = key.hashCode() % nodeCount
 *
 * THIS IS AN ANTI-PATTERN. Included only to demonstrate WHY consistent hashing
 * is necessary. In an interview, you should mention this approach and explain
 * why it fails at scale:
 *
 * PROBLEM 1: Adding/removing a node remaps ~(N-1)/N of all keys.
 *   - 3 nodes → 4 nodes: ~75% of keys move to different nodes
 *   - All those keys become cache misses → thundering herd to database
 *
 * PROBLEM 2: No way to gracefully drain a node.
 *   - Removing a node instantly invalidates all its keys
 *   - No concept of "migrate keys to neighbors"
 *
 * PROBLEM 3: Uneven distribution when node count is small.
 *   - hashCode() is not uniformly distributed
 *   - Some nodes get significantly more keys than others
 */
public class ModHashStrategy implements HashingStrategy {

    private final List<CacheNode> nodes;

    public ModHashStrategy() {
        this.nodes = new ArrayList<>();
    }

    @Override
    public Optional<CacheNode> getNode(String key) {
        if (nodes.isEmpty()) return Optional.empty();
        // The ENTIRE problem in one line:
        int index = Math.abs(key.hashCode() % nodes.size());
        return Optional.of(nodes.get(index));
    }

    @Override
    public void addNode(CacheNode node) {
        nodes.add(node);
        // WARNING: adding a node changes nodes.size(), which changes
        // key.hashCode() % nodes.size() for EVERY key. Cache stampede.
        System.out.printf("[MOD-HASH] Added node %s. Node count: %d. " +
                "WARNING: ~%d%% of keys are now mapped to wrong nodes!%n",
                node.getNodeId(), nodes.size(),
                (nodes.size() > 1) ? (100 * (nodes.size() - 1) / nodes.size()) : 0);
    }

    @Override
    public Set<String> removeNode(CacheNode node) {
        nodes.remove(node);
        System.out.printf("[MOD-HASH] Removed node %s. WARNING: ALL key mappings invalidated!%n",
                node.getNodeId());
        return Set.of();  // Cannot determine affected keys
    }

    @Override
    public List<CacheNode> getSuccessorNodes(String key, int count) {
        // Mod hashing has no concept of "successor nodes"
        // Replication doesn't work naturally with mod hashing
        return List.of();
    }

    @Override
    public int getNodeCount() { return nodes.size(); }

    @Override
    public List<CacheNode> getAllNodes() { return Collections.unmodifiableList(nodes); }
}
```

---

## 7. Service Layer Design

> The service layer follows the Facade pattern. CacheService is the single entry point that orchestrates store, eviction, hashing, and replication. Callers never interact with internals directly.

### 7.1 CacheService (Facade)

```
     ┌─────────────────────────────────────────────────────────────────┐
     │                  CacheService Call Flow                          │
     │                                                                  │
     │   Controller                                                     │
     │      │                                                           │
     │      ▼                                                           │
     │   CacheService (FACADE)                                          │
     │      │                                                           │
     │      ├──→ EvictionService                                        │
     │      │       └──→ EvictionStrategy (LRU/LFU/TTL)                │
     │      │                                                           │
     │      ├──→ CacheStore                                             │
     │      │       ├──→ InMemoryCacheStore (single-node)              │
     │      │       └──→ NodeAwareCacheStore                            │
     │      │               └──→ HashingStrategy (ConsistentHash)      │
     │      │                       └──→ InMemoryCacheStore (per-node) │
     │      │                                                           │
     │      └──→ ReplicationService                                     │
     │              └──→ HashingStrategy.getSuccessorNodes()            │
     └─────────────────────────────────────────────────────────────────┘
```

```java
/**
 * CacheService is the FACADE for the entire cache system.
 *
 * It hides the complexity of:
 *   - Eviction (which key to remove when full)
 *   - Hashing (which node to route to)
 *   - Replication (how to copy data for redundancy)
 *   - TTL (how to expire old entries)
 *   - Stats (hit/miss tracking)
 *
 * Callers (CacheController) only see: get(), put(), delete(), getStats().
 *
 * WIRING (set up by AppConfig):
 *   AppConfig creates:
 *     1. EvictionStrategy (based on config.evictionPolicy)
 *     2. EvictionService (wraps the strategy)
 *     3. CacheStore (InMemory or NodeAware)
 *     4. ReplicationService
 *     5. CacheService (receives all of the above via constructor)
 *
 * @param <K> key type (typically String)
 * @param <V> value type (generic)
 */
public class CacheService<K, V> {

    private final CacheStore<K, V> store;
    private final EvictionService<K> evictionService;
    private final ReplicationService replicationService;  // nullable for single-node mode
    private final CacheConfig config;

    // --- Stats ---
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong putCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);

    /**
     * Constructor injection -- all dependencies provided by AppConfig.
     * No framework needed: just plain Java constructors.
     */
    public CacheService(CacheStore<K, V> store,
                        EvictionService<K> evictionService,
                        ReplicationService replicationService,
                        CacheConfig config) {
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(evictionService, "evictionService must not be null");
        Objects.requireNonNull(config, "config must not be null");
        this.store = store;
        this.evictionService = evictionService;
        this.replicationService = replicationService;  // Can be null for single-node
        this.config = config;
    }

    /**
     * Retrieves a value from the cache.
     *
     * Flow:
     *   1. Check store for the key
     *   2. If found and not expired → HIT: notify eviction strategy, return value
     *   3. If not found or expired → MISS: return empty
     *
     * @param key the cache key
     * @return the cached value, or empty on miss
     */
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key must not be null");

        Optional<CacheEntry<K, V>> entryOpt = store.get(key);

        if (entryOpt.isEmpty()) {
            missCount.incrementAndGet();
            return Optional.empty();
        }

        CacheEntry<K, V> entry = entryOpt.get();

        // Check TTL expiration (lazy expiration)
        if (entry.isExpired()) {
            store.delete(key);
            evictionService.recordRemoval(key);
            missCount.incrementAndGet();
            return Optional.empty();
        }

        // Cache HIT: update access metadata
        entry.recordAccess();
        evictionService.recordAccess(key);
        hitCount.incrementAndGet();

        return Optional.of(entry.getValue());
    }

    /**
     * Stores a value in the cache.
     *
     * Flow:
     *   1. If at capacity → ask EvictionService to evict one entry
     *   2. Create CacheEntry with metadata (TTL, timestamps)
     *   3. Store in CacheStore
     *   4. Notify EvictionService of new insertion
     *   5. If distributed → replicate to successor nodes
     *
     * @param key   the cache key
     * @param value the value to cache
     */
    public void put(K key, V value) {
        put(key, value, config.getDefaultTtlMillis());
    }

    /**
     * Stores a value with a custom TTL.
     */
    public void put(K key, V value, long ttlMillis) {
        Objects.requireNonNull(key, "key must not be null");

        // Check if we need to evict (only for new keys, not updates)
        if (!store.contains(key)) {
            evictIfNeeded();
        }

        CacheEntry<K, V> entry = new CacheEntry<>(key, value, ttlMillis);
        store.put(key, entry);
        evictionService.recordInsertion(key);
        putCount.incrementAndGet();

        // Replicate to other nodes (if distributed mode)
        if (replicationService != null) {
            replicationService.replicatePut(String.valueOf(key), entry);
        }
    }

    /**
     * Deletes a key from the cache.
     *
     * @param key the cache key to delete
     * @return true if the key existed and was deleted
     */
    public boolean delete(K key) {
        Objects.requireNonNull(key, "key must not be null");
        boolean deleted = store.delete(key);
        if (deleted) {
            evictionService.recordRemoval(key);
            // Replicate deletion
            if (replicationService != null) {
                replicationService.replicateDelete(String.valueOf(key));
            }
        }
        return deleted;
    }

    /**
     * Returns cache statistics: hit ratio, miss count, eviction count, size.
     */
    public CacheStats getStats() {
        long hits = hitCount.get();
        long misses = missCount.get();
        long total = hits + misses;
        double hitRatio = total > 0 ? (double) hits / total : 0.0;

        return new CacheStats(hits, misses, hitRatio, putCount.get(),
                evictionCount.get(), store.size());
    }

    /** Clears the entire cache. */
    public void clear() {
        store.clear();
        evictionService.clear();
        hitCount.set(0);
        missCount.set(0);
        putCount.set(0);
        evictionCount.set(0);
    }

    // === Private ===

    /**
     * Triggers eviction if the cache is at capacity.
     * Delegates to EvictionService, which delegates to the EvictionStrategy.
     */
    private void evictIfNeeded() {
        while (store.size() >= config.getMaxEntriesPerNode()) {
            Optional<K> evictedKey = evictionService.evictIfNeeded(store.size());
            if (evictedKey.isPresent()) {
                store.delete(evictedKey.get());
                evictionCount.incrementAndGet();
                System.out.printf("[CACHE] Evicted key: %s%n", evictedKey.get());
            } else {
                break;  // Nothing to evict (shouldn't happen if capacity > 0)
            }
        }
    }

    /**
     * Inner record for cache statistics.
     * Immutable, returned by getStats().
     */
    public record CacheStats(
        long hits,
        long misses,
        double hitRatio,
        long puts,
        long evictions,
        int currentSize
    ) {
        @Override
        public String toString() {
            return String.format(
                "CacheStats[hits=%d, misses=%d, hitRatio=%.2f%%, puts=%d, evictions=%d, size=%d]",
                hits, misses, hitRatio * 100, puts, evictions, currentSize);
        }
    }
}
```

---

### 7.2 EvictionService (Eviction Lifecycle Manager)

```java
/**
 * Manages the eviction lifecycle by wrapping an EvictionStrategy.
 *
 * WHY a separate service instead of calling EvictionStrategy directly?
 *   - Adds capacity checking logic (strategy doesn't know about maxSize)
 *   - Provides a consistent API regardless of which strategy is active
 *   - Centralizes eviction logging and metrics
 *   - Follows SRP: strategy knows HOW to evict, service knows WHEN
 *
 * @param <K> key type
 */
public class EvictionService<K> {

    private final EvictionStrategy<K> strategy;
    private final int maxSize;

    /**
     * @param strategy the eviction strategy (LRU, LFU, TTL)
     * @param maxSize  maximum cache entries (from CacheConfig)
     */
    public EvictionService(EvictionStrategy<K> strategy, int maxSize) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        this.strategy = strategy;
        this.maxSize = maxSize;
    }

    /** Notifies the strategy of a cache read (GET hit). */
    public void recordAccess(K key) {
        strategy.onGet(key);
    }

    /** Notifies the strategy of a cache write (PUT). */
    public void recordInsertion(K key) {
        strategy.onPut(key);
    }

    /**
     * Checks if eviction is needed and performs it.
     *
     * @param currentSize the current number of entries in the store
     * @return the evicted key, or empty if no eviction was needed
     */
    public Optional<K> evictIfNeeded(int currentSize) {
        if (currentSize < maxSize) {
            return Optional.empty();  // Under capacity, no eviction needed
        }
        return strategy.evict();
    }

    /** Forces an eviction regardless of capacity. Used for testing. */
    public Optional<K> forceEvict() {
        return strategy.evict();
    }

    /** Notifies the strategy that a key was explicitly deleted. */
    public void recordRemoval(K key) {
        strategy.remove(key);
    }

    /** Clears all eviction tracking data. */
    public void clear() {
        strategy.clear();
    }

    /** Returns the strategy name for logging/stats. */
    public String getStrategyName() {
        return strategy.getStrategyName();
    }
}
```

---

### 7.3 ReplicationService (Conceptual Node Replication)

```java
/**
 * Handles data replication across cache nodes for fault tolerance.
 *
 * In a real distributed cache (Redis Cluster, Memcached), replication ensures
 * that if a node goes down, its data is available on replica nodes.
 *
 * This is a CONCEPTUAL implementation for interview discussion. In production,
 * this would involve:
 *   - Async replication (primary returns immediately, replicas update in background)
 *   - Quorum writes (W replicas must acknowledge before returning success)
 *   - Anti-entropy (background reconciliation of inconsistent replicas)
 *   - Conflict resolution (last-write-wins, vector clocks, CRDTs)
 *
 * For this LLD, we demonstrate the routing logic:
 *   - Find N successor nodes on the consistent hashing ring
 *   - Conceptually "write" to each of them
 */
public class ReplicationService {

    private final HashingStrategy hashingStrategy;
    private final int replicaCount;

    public ReplicationService(HashingStrategy hashingStrategy, int replicaCount) {
        this.hashingStrategy = hashingStrategy;
        this.replicaCount = replicaCount;
    }

    /**
     * Replicates a PUT operation to successor nodes.
     *
     * Flow:
     *   1. Find the next 'replicaCount' distinct physical nodes clockwise from the key
     *   2. For each replica node: write the entry (simulated here with logging)
     *
     * In production: this would be async with retry logic and timeout.
     */
    public void replicatePut(String key, CacheEntry<?, ?> entry) {
        List<CacheNode> replicas = getReplicaNodes(key);
        for (CacheNode replica : replicas) {
            System.out.printf("[REPLICATION] PUT key=%s → replica node %s%n",
                    key, replica.getNodeId());
            // In production: send PUT request to replica node over network
        }
    }

    /**
     * Replicates a DELETE operation to successor nodes.
     */
    public void replicateDelete(String key) {
        List<CacheNode> replicas = getReplicaNodes(key);
        for (CacheNode replica : replicas) {
            System.out.printf("[REPLICATION] DELETE key=%s → replica node %s%n",
                    key, replica.getNodeId());
        }
    }

    /**
     * Returns the replica nodes for a given key.
     * Skips the primary node (the one that owns the key).
     *
     * Example with replicaCount=2 and 5 nodes:
     *   Key "user1" → primary: node-3
     *   Successors on ring: node-4, node-1
     *   Replicas: [node-4, node-1]
     */
    public List<CacheNode> getReplicaNodes(String key) {
        return hashingStrategy.getSuccessorNodes(key, replicaCount);
    }
}
```

---

## 8. Concurrency Considerations

> Distributed caches are inherently concurrent: multiple clients issue GET/PUT simultaneously. This section covers how each component handles thread safety.

### 8.1 Overview: What Needs Thread Safety and Why

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                    CONCURRENCY MODEL                             │
     │                                                                  │
     │   Component              Thread Safety Mechanism                 │
     │   ─────────────────────  ────────────────────────────────────    │
     │   InMemoryCacheStore     ConcurrentHashMap (lock striping)      │
     │   LRUEvictionStrategy    ReentrantLock (exclusive access to DLL)│
     │   LFUEvictionStrategy    ReentrantLock (exclusive access to     │
     │                            frequency buckets)                    │
     │   TTLEvictionStrategy    ReentrantLock + daemon thread safety    │
     │   ConsistentHashStrategy ReadWriteLock (readers concurrent,     │
     │                            writers exclusive)                    │
     │   CacheEntry             volatile fields (lastAccessedAt, freq) │
     │   CacheService stats     AtomicLong (lock-free counters)        │
     │   CacheNode.status       volatile (visibility across threads)   │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.2 ConcurrentHashMap and Lock Striping

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                LOCK STRIPING (ConcurrentHashMap)                 │
     │                                                                  │
     │   NAIVE APPROACH: one lock for the entire map                   │
     │     synchronized(map) { map.get(key); }                         │
     │     Problem: thread-1 reading "user1" blocks thread-2           │
     │     reading "user2" -- no parallelism at all                    │
     │                                                                  │
     │   LOCK STRIPING: ConcurrentHashMap divides into segments         │
     │                                                                  │
     │     Segment 0     Segment 1     Segment 2     Segment 3        │
     │     ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐    │
     │     │ Lock 0  │   │ Lock 1  │   │ Lock 2  │   │ Lock 3  │    │
     │     │─────────│   │─────────│   │─────────│   │─────────│    │
     │     │ "user1" │   │ "user2" │   │ "user3" │   │ "user4" │    │
     │     │ "user5" │   │ "user6" │   │ "user7" │   │ "user8" │    │
     │     └─────────┘   └─────────┘   └─────────┘   └─────────┘    │
     │                                                                  │
     │     Thread-1 locks Segment 0 to read "user1"                    │
     │     Thread-2 locks Segment 1 to read "user2"  ← PARALLEL!     │
     │     Thread-3 locks Segment 2 to write "user3" ← PARALLEL!     │
     │                                                                  │
     │   Java 8+: ConcurrentHashMap uses CAS + synchronized on        │
     │   individual bins (tree/list heads), not segments. Even finer!  │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.3 InMemoryCacheStore Thread Safety

```java
/**
 * Thread-safe in-memory cache store backed by ConcurrentHashMap.
 *
 * WHY ConcurrentHashMap instead of synchronized HashMap?
 *   - ConcurrentHashMap allows concurrent reads (no locking for get)
 *   - Writes lock only the affected bin (lock striping), not the whole map
 *   - Throughput: ~16x better than synchronized HashMap under contention
 *
 * WHY NOT just Collections.synchronizedMap()?
 *   - synchronizedMap wraps every method in synchronized(mutex)
 *   - Only one thread can access the map at a time (no read parallelism)
 *   - ConcurrentHashMap allows reads to proceed in parallel with writes
 */
public class InMemoryCacheStore<K, V> implements CacheStore<K, V> {

    private final ConcurrentHashMap<K, CacheEntry<K, V>> store;

    public InMemoryCacheStore() {
        this.store = new ConcurrentHashMap<>();
    }

    public InMemoryCacheStore(int initialCapacity) {
        this.store = new ConcurrentHashMap<>(initialCapacity);
    }

    @Override
    public Optional<CacheEntry<K, V>> get(K key) {
        CacheEntry<K, V> entry = store.get(key);
        if (entry == null) return Optional.empty();

        // Lazy TTL check: if expired, delete and return empty
        if (entry.isExpired()) {
            store.remove(key);
            return Optional.empty();
        }

        return Optional.of(entry);
    }

    @Override
    public void put(K key, CacheEntry<K, V> entry) {
        store.put(key, entry);
    }

    @Override
    public boolean delete(K key) {
        return store.remove(key) != null;
    }

    @Override
    public boolean contains(K key) {
        CacheEntry<K, V> entry = store.get(key);
        return entry != null && !entry.isExpired();
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public void clear() {
        store.clear();
    }

    @Override
    public Set<K> keys() {
        return store.keySet();
    }
}
```

### 8.4 ReadWriteLock in ConsistentHashStrategy

```
     ┌──────────────────────────────────────────────────────────────────┐
     │              ReadWriteLock in Consistent Hashing                  │
     │                                                                  │
     │   WHY ReadWriteLock instead of ReentrantLock?                    │
     │                                                                  │
     │   getNode() is called on EVERY cache request (very hot path).   │
     │   addNode()/removeNode() is called VERY rarely (scale events).  │
     │                                                                  │
     │   With ReentrantLock:                                            │
     │     getNode("k1")  ──BLOCKED──  getNode("k2")  ← unnecessary! │
     │                                                                  │
     │   With ReadWriteLock:                                            │
     │     getNode("k1")  ──PARALLEL── getNode("k2")  ← both read!   │
     │     addNode(D)      ──EXCLUSIVE── (blocks all reads + writes)   │
     │                                                                  │
     │   Reads can proceed concurrently (shared lock).                 │
     │   Writes get exclusive access (only during topology changes).   │
     │   Since topology changes are rare (once per scale event),       │
     │   this gives us near-lock-free performance for the hot path.    │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.5 Race Condition: Double Eviction

```
     ┌──────────────────────────────────────────────────────────────────┐
     │              RACE CONDITION: Double Eviction                      │
     │                                                                  │
     │   Thread-1: put("key-1000") → cache full → evicts "key-500"    │
     │   Thread-2: put("key-1001") → cache full → evicts "key-500"    │
     │                                                                  │
     │   If not synchronized, both threads evict the same key,         │
     │   but only one entry is actually freed. Cache stays full.       │
     │                                                                  │
     │   SOLUTION: The eviction strategy uses ReentrantLock.           │
     │   Thread-2's evict() call returns a DIFFERENT key because       │
     │   Thread-1 already removed "key-500" from the DLL/bucket.      │
     │                                                                  │
     │   The CacheService.evictIfNeeded() loop also handles this:      │
     │   it loops until store.size() < maxSize, so if an evict()       │
     │   returns a key that's already gone, it just tries again.       │
     └──────────────────────────────────────────────────────────────────┘
```

---

## 9. SOLID Principles Applied

| Principle | Where Applied | Example |
|-----------|--------------|---------|
| **S** - Single Responsibility | Each eviction strategy | LRUEvictionStrategy ONLY knows LRU logic. It does not know about TTL, store capacity, or hashing. |
| **S** - Single Responsibility | EvictionService vs EvictionStrategy | Strategy knows HOW to evict (data structure operations). Service knows WHEN to evict (capacity checks). |
| **O** - Open/Closed | EvictionStrategy interface | Adding a new eviction policy (e.g., FIFO, Random, ARC) requires ONE new class implementing EvictionStrategy. Zero changes to CacheService, EvictionService, or any other existing class. |
| **O** - Open/Closed | HashingStrategy interface | Adding a new hashing algorithm (e.g., jump hash, rendezvous hash) requires ONE new class. NodeAwareCacheStore works with any HashingStrategy without modification. |
| **L** - Liskov Substitution | EvictionStrategy implementations | CacheService works identically whether the strategy is LRU, LFU, or TTL. Swapping `new LRUEvictionStrategy(1000)` with `new LFUEvictionStrategy(1000)` requires ZERO code changes in CacheService. |
| **L** - Liskov Substitution | CacheStore implementations | CacheService depends on `CacheStore<K,V>` interface. InMemoryCacheStore and NodeAwareCacheStore are interchangeable. Switching from single-node to distributed is a config change, not a code change. |
| **I** - Interface Segregation | Small, focused interfaces | EvictionStrategy has 7 methods (all related to eviction). HashingStrategy has 6 methods (all related to routing). No "god interface" that forces unrelated methods. |
| **I** - Interface Segregation | CacheStore vs CacheRepository | CacheStore handles runtime caching. CacheRepository handles backing-store persistence. A class that only needs caching does not depend on persistence methods. |
| **D** - Dependency Inversion | CacheService constructor | CacheService depends on CacheStore (interface), not InMemoryCacheStore (concrete). The dependency is injected by AppConfig. CacheService never does `new InMemoryCacheStore()`. |
| **D** - Dependency Inversion | AppConfig as composition root | AppConfig is the ONLY class that creates concrete objects. All other classes depend on abstractions (interfaces). Changing implementations is a one-line change in AppConfig. |

---

## 10. Sample Workflows

### 10.1 Cache GET (Hit)

```
     Step  Action                                   Component
     ────  ─────────────────────────────────────────  ────────────────────
     1     Client calls: controller.get("user:123")  CacheController
     2     Controller delegates: service.get("user:123")  CacheService
     3     Service calls: store.get("user:123")      InMemoryCacheStore
     4     Store returns: Optional.of(CacheEntry)    InMemoryCacheStore
     5     Service checks: entry.isExpired() → false CacheService
     6     Service calls: entry.recordAccess()       CacheEntry
           (bumps frequency, updates lastAccessedAt)
     7     Service calls: evictionService.recordAccess("user:123")  EvictionService
     8     EvictionService delegates: strategy.onGet("user:123")    LRUEvictionStrategy
     9     LRU: map.get("user:123") → DLLNode       LRUEvictionStrategy
           removeNode(node), addFirst(node)
           [moves "user:123" to head of DLL = most recently used]
     10    Service increments: hitCount.incrementAndGet()  CacheService
     11    Service returns: Optional.of(entry.getValue())  → Controller → Client
```

### 10.2 Cache GET (Miss)

```
     Step  Action                                   Component
     ────  ─────────────────────────────────────────  ────────────────────
     1     Client calls: controller.get("user:999")  CacheController
     2     Controller delegates: service.get("user:999")  CacheService
     3     Service calls: store.get("user:999")      InMemoryCacheStore
     4     Store returns: Optional.empty()            InMemoryCacheStore
     5     Service increments: missCount.incrementAndGet()  CacheService
     6     Service returns: Optional.empty()          → Controller → Client
```

### 10.3 Cache PUT (with Eviction)

```
     Step  Action                                   Component
     ────  ─────────────────────────────────────────  ────────────────────
     1     Client calls: controller.put("user:456", userData)  CacheController
     2     Controller delegates: service.put("user:456", userData)  CacheService
     3     Service checks: store.contains("user:456") → false (new key)  CacheService
     4     Service checks: store.size() >= maxEntries → TRUE (full!)  CacheService
     5     Service calls: evictionService.evictIfNeeded(currentSize)  EvictionService
     6     EvictionService delegates: strategy.evict()  LRUEvictionStrategy
     7     LRU: removes tail.prev (least recently used)  LRUEvictionStrategy
           → returns Optional.of("user:001")
     8     Service calls: store.delete("user:001")   InMemoryCacheStore
     9     Service increments: evictionCount.incrementAndGet()  CacheService
     10    Service creates: new CacheEntry("user:456", userData, ttl)  CacheService
     11    Service calls: store.put("user:456", entry)  InMemoryCacheStore
     12    Service calls: evictionService.recordInsertion("user:456")  EvictionService
     13    EvictionService delegates: strategy.onPut("user:456")  LRUEvictionStrategy
     14    LRU: creates DLLNode, map.put, addFirst  LRUEvictionStrategy
     15    Service calls: replicationService.replicatePut(...)  ReplicationService
     16    Replication: finds successor nodes, sends PUT  ReplicationService
     17    Service increments: putCount.incrementAndGet()  CacheService
```

### 10.4 Node Addition (Scale Out)

```
     Step  Action                                   Component
     ────  ─────────────────────────────────────────  ────────────────────
     1     Operator adds node: hashingStrategy.addNode(newNode)  ConsistentHashStrategy
     2     Strategy creates 150 virtual nodes:       ConsistentHashStrategy
           for i in 0..149:
             hash = MD5("node-4#" + i)
             ring.put(hash, new VirtualNode(hash, newNode, i))
     3     Ring now has old_vnodes + 150 new points  ConsistentHashStrategy
     4     Some keys that previously mapped to        (automatic)
           node-1, node-2, node-3 now map to node-4
           (only keys whose hash falls between
           the new vnodes and the next clockwise vnode)
     5     (Optional) Migration: for affected keys,  NodeAwareCacheStore
           read from old node, write to new node,
           delete from old node

     Key redistribution:
     Before: ring has 450 vnodes (3 nodes x 150)
     After:  ring has 600 vnodes (4 nodes x 150)
     Only ~1/4 of keys move to node-4 (not 75% like mod hashing!)
```

### 10.5 Node Removal (Scale In / Failure)

```
     Step  Action                                   Component
     ────  ─────────────────────────────────────────  ────────────────────
     1     Health check detects: node-2 is DOWN      (external monitor)
     2     Operator calls: hashingStrategy.removeNode(node2)  ConsistentHashStrategy
     3     Strategy removes 150 virtual nodes:       ConsistentHashStrategy
           for i in 0..149:
             hash = MD5("node-2#" + i)
             ring.remove(hash)
     4     Ring now has 300 vnodes (2 remaining nodes)  ConsistentHashStrategy
     5     Keys that were on node-2 now automatically  (automatic)
           map to the next clockwise nodes (node-1 or node-3)
     6     If replicas exist: replica nodes already    ReplicationService
           have copies of node-2's data → no data loss
     7     If no replicas: those keys are cache misses  (data loss)
           until reloaded from backing store

     Key redistribution:
     Before: 450 vnodes (3 nodes)
     After:  300 vnodes (2 nodes)
     Only ~1/3 of keys are affected (the ones that were on node-2)
     The other ~2/3 of keys still map to the same nodes as before
```

---

## 11. Design Patterns Used

| Pattern | Where Used | Why |
|---------|-----------|-----|
| **Strategy** | EvictionStrategy (LRU, LFU, TTL) | Swappable eviction algorithms. CacheService does not know which policy is active. Adding a new policy is ONE new class, ZERO changes elsewhere. |
| **Strategy** | HashingStrategy (Consistent, Mod) | Swappable node routing. NodeAwareCacheStore delegates to the strategy without knowing the algorithm. |
| **Facade** | CacheService | Hides the complexity of store + eviction + replication + stats behind a simple get/put/delete API. Controller talks ONLY to CacheService. |
| **Builder** | CacheConfig.Builder | CacheConfig has 6 parameters with sensible defaults. Builder avoids telescoping constructors (`new CacheConfig(10000, "LRU", 300000, 150, 2, 60000)`). |
| **Factory Method** | AppConfig | Creates the correct EvictionStrategy based on config string. `"LRU"` → `new LRUEvictionStrategy(...)`. Centralizes object creation. |
| **Observer** (conceptual) | TTLEvictionStrategy.onExpireCallback | When an entry expires, the TTL strategy notifies the store to delete the actual entry. Decouples expiration logic from storage. |
| **Singleton** (conceptual) | KeyGenerator for entry IDs | If cache entries need unique IDs, a thread-safe AtomicLong-based generator ensures uniqueness. |
| **Template Method** (conceptual) | EvictionStrategy contract | The interface defines the template (onGet, onPut, evict, remove). Each implementation fills in the algorithm differently, but the lifecycle (when to call each method) is fixed in EvictionService. |
| **Proxy** (conceptual) | NodeAwareCacheStore | Acts as a proxy to the correct InMemoryCacheStore based on consistent hashing. Callers see a single CacheStore, but data is distributed. |
| **Decorator** (extensibility) | Could wrap CacheStore for logging | `LoggingCacheStore` could wrap any CacheStore and log all operations without modifying the original store. Decorator pattern for cross-cutting concerns. |

---

## 12. Extensibility Points

> A well-designed system should be easy to extend without modifying existing code (Open-Closed Principle). Here is how to extend each axis of the distributed cache.

### 12.1 Adding a New Eviction Policy (e.g., FIFO, Random, W-TinyLFU)

**Steps:**
1. Create a new class implementing `EvictionStrategy<K>`
2. Implement all 7 methods (onGet, onPut, evict, remove, size, clear, getStrategyName)
3. Add a case in `AppConfig` factory method

```java
// Step 1: New class
public class FIFOEvictionStrategy<K> implements EvictionStrategy<K> {
    private final Queue<K> queue = new LinkedList<>();
    private final Set<K> keySet = new HashSet<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public void onGet(K key) { /* FIFO ignores access order */ }

    @Override
    public void onPut(K key) {
        lock.lock();
        try {
            if (keySet.add(key)) { queue.offer(key); }
        } finally { lock.unlock(); }
    }

    @Override
    public Optional<K> evict() {
        lock.lock();
        try {
            K key = queue.poll();
            if (key != null) { keySet.remove(key); return Optional.of(key); }
            return Optional.empty();
        } finally { lock.unlock(); }
    }

    @Override public void remove(K key) { lock.lock(); try { queue.remove(key); keySet.remove(key); } finally { lock.unlock(); } }
    @Override public int size() { return keySet.size(); }
    @Override public void clear() { queue.clear(); keySet.clear(); }
    @Override public String getStrategyName() { return "FIFO"; }
}

// Step 2: Add to AppConfig factory (one-line change)
// In AppConfig.createEvictionStrategy():
case "FIFO" -> new FIFOEvictionStrategy<>(config.getMaxEntriesPerNode());
```

**What does NOT change:** CacheService, EvictionService, CacheStore, CacheController, all existing strategy classes. ZERO modifications to existing code.

---

### 12.2 Adding a New Hashing Algorithm (e.g., Jump Hash, Rendezvous Hash)

**Steps:**
1. Create a new class implementing `HashingStrategy`
2. Implement all 6 methods
3. Use it in `AppConfig` instead of ConsistentHashStrategy

```java
// Rendezvous hashing: each key computes a score for each node,
// highest score wins. No ring needed.
public class RendezvousHashStrategy implements HashingStrategy {
    private final List<CacheNode> nodes = new CopyOnWriteArrayList<>();

    @Override
    public Optional<CacheNode> getNode(String key) {
        return nodes.stream()
            .filter(CacheNode::isAvailable)
            .max(Comparator.comparingInt(node -> hash(key + node.getNodeId())));
    }
    // ... implement remaining methods ...
}
```

---

### 12.3 Adding a New Cache Store (e.g., Off-Heap, Disk-Backed)

```java
// Off-heap store using ByteBuffer (for large caches that don't want GC pressure)
public class OffHeapCacheStore<K, V> implements CacheStore<K, V> {
    private final ByteBuffer buffer;
    // Serialize/deserialize entries into off-heap memory
    // CacheService works with this identically to InMemoryCacheStore
}
```

---

### 12.4 Adding Cache-Aside (Read-Through / Write-Through)

Wrap the existing CacheService to add backing store interaction:

```java
/**
 * Adds read-through behavior: on cache miss, load from backing store.
 * Uses CacheRepository (interface) to abstract the backing store.
 *
 * Wiring in AppConfig:
 *   CacheRepository repo = new InMemoryCacheRepository();
 *   CacheService baseService = new CacheService(store, eviction, replication, config);
 *   ReadThroughCacheService service = new ReadThroughCacheService(baseService, repo);
 */
public class ReadThroughCacheService<K, V> {
    private final CacheService<K, V> cacheService;
    private final CacheRepository<K, V> repository;

    public Optional<V> get(K key) {
        // Try cache first
        Optional<V> cached = cacheService.get(key);
        if (cached.isPresent()) return cached;

        // Cache miss: load from backing store
        Optional<V> fromBacking = repository.loadFromBacking(key);
        fromBacking.ifPresent(value -> cacheService.put(key, value));
        return fromBacking;
    }
}
```

---

### 12.5 AppConfig: The Composition Root

```java
/**
 * AppConfig is the COMPOSITION ROOT of the distributed cache system.
 *
 * It creates ALL objects and injects ALL dependencies.
 * No other class uses 'new' for service-layer objects.
 *
 * WHY manual wiring instead of Spring/Guice?
 *   - Interview clarity: you see exactly what depends on what
 *   - No magic: no annotations, no classpath scanning
 *   - Java 21, plain Java: the requirement
 *
 * CALL CHAIN (what depends on what):
 *
 *   AppConfig
 *     ├── creates CacheConfig
 *     ├── creates EvictionStrategy (based on config.evictionPolicy)
 *     │     └── LRUEvictionStrategy / LFUEvictionStrategy / TTLEvictionStrategy
 *     ├── creates EvictionService(strategy, maxSize)
 *     ├── creates CacheNode[] (physical nodes)
 *     ├── creates HashingStrategy (ConsistentHashStrategy)
 *     │     └── addNode() for each CacheNode
 *     ├── creates InMemoryCacheStore (one per node)
 *     ├── creates NodeAwareCacheStore(hashingStrategy, nodeStores)
 *     ├── creates ReplicationService(hashingStrategy, replicaCount)
 *     ├── creates CacheService(store, evictionService, replicationService, config)
 *     ├── creates CacheStatsDisplay(cacheService)
 *     └── creates CacheController(cacheService)
 */
public class AppConfig {

    private final CacheConfig config;
    private final CacheService<String, String> cacheService;
    private final CacheController controller;
    private final CacheStatsDisplay statsDisplay;

    public AppConfig() {
        // 1. Configuration
        this.config = CacheConfig.builder()
                .maxEntriesPerNode(1000)
                .evictionPolicy("LRU")
                .defaultTtlMillis(300_000)     // 5 minutes
                .virtualNodeCount(150)
                .replicaCount(2)
                .cleanupIntervalMillis(60_000) // 1 minute
                .build();

        // 2. Eviction Strategy (Factory Method based on config)
        EvictionStrategy<String> evictionStrategy = createEvictionStrategy(config);

        // 3. Eviction Service (wraps strategy + adds capacity logic)
        EvictionService<String> evictionService =
                new EvictionService<>(evictionStrategy, config.getMaxEntriesPerNode());

        // 4. Physical Nodes
        CacheNode node1 = new CacheNode("node-1", "192.168.1.1", 6379);
        CacheNode node2 = new CacheNode("node-2", "192.168.1.2", 6379);
        CacheNode node3 = new CacheNode("node-3", "192.168.1.3", 6379);

        // 5. Hashing Strategy (Consistent Hashing with virtual nodes)
        ConsistentHashStrategy hashingStrategy = new ConsistentHashStrategy(config.getVirtualNodeCount());
        hashingStrategy.addNode(node1);
        hashingStrategy.addNode(node2);
        hashingStrategy.addNode(node3);

        // 6. Cache Stores (one per physical node)
        Map<String, InMemoryCacheStore<String, String>> nodeStores = new HashMap<>();
        nodeStores.put("node-1", new InMemoryCacheStore<>());
        nodeStores.put("node-2", new InMemoryCacheStore<>());
        nodeStores.put("node-3", new InMemoryCacheStore<>());

        // 7. Node-Aware Store (routes to correct node via hashing)
        NodeAwareCacheStore<String, String> store =
                new NodeAwareCacheStore<>(hashingStrategy, nodeStores);

        // 8. Replication Service
        ReplicationService replicationService =
                new ReplicationService(hashingStrategy, config.getReplicaCount());

        // 9. Cache Service (FACADE - the main entry point)
        this.cacheService = new CacheService<>(store, evictionService, replicationService, config);

        // 10. Display + Controller
        this.statsDisplay = new CacheStatsDisplay(cacheService);
        this.controller = new CacheController(cacheService);
    }

    /**
     * Factory Method: creates the correct EvictionStrategy based on config string.
     *
     * To add a new policy:
     *   1. Create a class implementing EvictionStrategy<K>
     *   2. Add a case here
     * That's it. CacheService, EvictionService, etc. need ZERO changes.
     */
    private EvictionStrategy<String> createEvictionStrategy(CacheConfig config) {
        return switch (config.getEvictionPolicy().toUpperCase()) {
            case "LRU" -> new LRUEvictionStrategy<>(config.getMaxEntriesPerNode());
            case "LFU" -> new LFUEvictionStrategy<>(config.getMaxEntriesPerNode());
            case "TTL" -> new TTLEvictionStrategy<>(
                    config.getDefaultTtlMillis(),
                    config.getCleanupIntervalMillis()
            );
            default -> throw new IllegalArgumentException(
                    "Unknown eviction policy: " + config.getEvictionPolicy());
        };
    }

    // --- Getters for demo/testing ---
    public CacheService<String, String> getCacheService()   { return cacheService; }
    public CacheController getController()                   { return controller; }
    public CacheStatsDisplay getStatsDisplay()               { return statsDisplay; }
}
```

---

### 12.6 Complete Dependency Wiring Diagram

```
     AppConfig (creates everything)
     │
     ├── CacheConfig (immutable, shared by all)
     │
     ├── EvictionStrategy ←── switch(config.evictionPolicy)
     │     ├── LRUEvictionStrategy(maxSize)
     │     ├── LFUEvictionStrategy(maxSize)
     │     └── TTLEvictionStrategy(defaultTTL, cleanupInterval)
     │
     ├── EvictionService(evictionStrategy, maxSize)
     │
     ├── CacheNode[] (node-1, node-2, node-3)
     │
     ├── ConsistentHashStrategy(vnodeCount)
     │     └── addNode(node-1), addNode(node-2), addNode(node-3)
     │
     ├── InMemoryCacheStore (per node: node-1, node-2, node-3)
     │
     ├── NodeAwareCacheStore(hashingStrategy, nodeStores)
     │
     ├── ReplicationService(hashingStrategy, replicaCount)
     │
     ├── CacheService(store, evictionService, replicationService, config)  ← FACADE
     │
     ├── CacheStatsDisplay(cacheService)
     │
     └── CacheController(cacheService)

     Rule: arrows point DOWN. No class creates objects above it.
     Rule: all classes depend on interfaces, not concrete implementations.
     Rule: only AppConfig uses 'new' for service-layer objects.
```

### 12.7 NodeAwareCacheStore (Routing Store)

```java
/**
 * A CacheStore that routes operations to the correct physical node's
 * InMemoryCacheStore using the HashingStrategy.
 *
 * This is the bridge between the single-node CacheStore abstraction and
 * the distributed multi-node reality. Callers (CacheService) see a single
 * store; internally, data is spread across N node stores.
 *
 * Acts as a PROXY: same interface, but delegates to the right target.
 */
public class NodeAwareCacheStore<K, V> implements CacheStore<K, V> {

    private final HashingStrategy hashingStrategy;
    private final Map<String, InMemoryCacheStore<K, V>> nodeStores;

    public NodeAwareCacheStore(HashingStrategy hashingStrategy,
                                Map<String, InMemoryCacheStore<K, V>> nodeStores) {
        this.hashingStrategy = hashingStrategy;
        this.nodeStores = nodeStores;
    }

    @Override
    public Optional<CacheEntry<K, V>> get(K key) {
        InMemoryCacheStore<K, V> store = resolveStore(key);
        return store.get(key);
    }

    @Override
    public void put(K key, CacheEntry<K, V> entry) {
        InMemoryCacheStore<K, V> store = resolveStore(key);
        store.put(key, entry);
    }

    @Override
    public boolean delete(K key) {
        InMemoryCacheStore<K, V> store = resolveStore(key);
        return store.delete(key);
    }

    @Override
    public boolean contains(K key) {
        InMemoryCacheStore<K, V> store = resolveStore(key);
        return store.contains(key);
    }

    @Override
    public int size() {
        return nodeStores.values().stream()
                .mapToInt(InMemoryCacheStore::size)
                .sum();
    }

    @Override
    public void clear() {
        nodeStores.values().forEach(InMemoryCacheStore::clear);
    }

    @Override
    public Set<K> keys() {
        Set<K> allKeys = new HashSet<>();
        nodeStores.values().forEach(store -> allKeys.addAll(store.keys()));
        return allKeys;
    }

    /**
     * Resolves the correct node store for a given key.
     *
     * Flow:
     *   1. Hash the key → find the owning CacheNode via HashingStrategy
     *   2. Look up that node's InMemoryCacheStore
     *   3. Return the store (caller performs the actual get/put/delete)
     *
     * @throws NodeUnavailableException if no node is available for the key
     */
    private InMemoryCacheStore<K, V> resolveStore(K key) {
        Optional<CacheNode> nodeOpt = hashingStrategy.getNode(String.valueOf(key));
        if (nodeOpt.isEmpty()) {
            throw new NodeUnavailableException("No available node for key: " + key);
        }

        CacheNode node = nodeOpt.get();
        InMemoryCacheStore<K, V> store = nodeStores.get(node.getNodeId());
        if (store == null) {
            throw new NodeUnavailableException(
                    "No store found for node: " + node.getNodeId());
        }

        return store;
    }
}
```

### 12.8 Exception Classes

```java
/** Base exception for all cache-related errors. */
public class CacheException extends RuntimeException {
    public CacheException(String message) { super(message); }
    public CacheException(String message, Throwable cause) { super(message, cause); }
}

/** Thrown when the cache is at capacity and eviction cannot free space. */
public class CacheFullException extends CacheException {
    public CacheFullException(String message) { super(message); }
}

/** Thrown when a requested key is not found in the cache. */
public class KeyNotFoundException extends CacheException {
    private final String key;
    public KeyNotFoundException(String key) {
        super("Key not found: " + key);
        this.key = key;
    }
    public String getKey() { return key; }
}

/** Thrown when the target cache node is down or unreachable. */
public class NodeUnavailableException extends CacheException {
    public NodeUnavailableException(String message) { super(message); }
}
```

### 12.9 CacheStatsDisplay

```java
/**
 * Formats and displays cache statistics in a human-readable ASCII format.
 * Used for monitoring and debugging the cache cluster.
 */
public class CacheStatsDisplay {

    private final CacheService<?, ?> cacheService;

    public CacheStatsDisplay(CacheService<?, ?> cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * Prints a formatted stats display to stdout.
     */
    public void display() {
        CacheService.CacheStats stats = cacheService.getStats();

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║          CACHE STATISTICS                ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.printf( "║  Hits:        %,10d                 ║%n", stats.hits());
        System.out.printf( "║  Misses:      %,10d                 ║%n", stats.misses());
        System.out.printf( "║  Hit Ratio:   %9.2f%%                ║%n", stats.hitRatio() * 100);
        System.out.printf( "║  Puts:        %,10d                 ║%n", stats.puts());
        System.out.printf( "║  Evictions:   %,10d                 ║%n", stats.evictions());
        System.out.printf( "║  Current Size:%,10d                 ║%n", stats.currentSize());
        System.out.println("╚══════════════════════════════════════════╝");
    }
}
```

### 12.10 CacheController

```java
/**
 * REST-like API entry point for the distributed cache.
 *
 * In a real system, this would be a proper HTTP handler (Javalin, HttpServer, etc.).
 * For this LLD, it provides a clean API that maps to CacheService methods.
 *
 * Wiring: AppConfig → CacheController(cacheService)
 */
public class CacheController {

    private final CacheService<String, String> cacheService;

    public CacheController(CacheService<String, String> cacheService) {
        this.cacheService = cacheService;
    }

    /** GET /cache/{key} */
    public Optional<String> get(String key) {
        System.out.printf("[API] GET /cache/%s%n", key);
        return cacheService.get(key);
    }

    /** PUT /cache/{key} with body */
    public void put(String key, String value) {
        System.out.printf("[API] PUT /cache/%s%n", key);
        cacheService.put(key, value);
    }

    /** PUT /cache/{key}?ttl={millis} with body */
    public void put(String key, String value, long ttlMillis) {
        System.out.printf("[API] PUT /cache/%s (ttl=%dms)%n", key, ttlMillis);
        cacheService.put(key, value, ttlMillis);
    }

    /** DELETE /cache/{key} */
    public boolean delete(String key) {
        System.out.printf("[API] DELETE /cache/%s%n", key);
        return cacheService.delete(key);
    }

    /** GET /cache/stats */
    public CacheService.CacheStats stats() {
        System.out.println("[API] GET /cache/stats");
        return cacheService.getStats();
    }
}
```

### 12.11 InMemoryCacheRepository

```java
/**
 * Simulates a backing store (database, file system, external API).
 *
 * In the cache-aside pattern:
 *   Cache MISS → loadFromBacking(key) → populate cache → return to caller
 *
 * This implementation uses ConcurrentHashMap to simulate a database.
 * In production, this would be JDBC, JPA, or an HTTP client.
 */
public class InMemoryCacheRepository<K, V> implements CacheRepository<K, V> {

    private final ConcurrentHashMap<K, V> backingStore = new ConcurrentHashMap<>();

    @Override
    public Optional<V> loadFromBacking(K key) {
        System.out.printf("[BACKING-STORE] Loading key=%s%n", key);
        return Optional.ofNullable(backingStore.get(key));
    }

    @Override
    public void writeToBacking(K key, V value) {
        System.out.printf("[BACKING-STORE] Writing key=%s%n", key);
        backingStore.put(key, value);
    }

    @Override
    public boolean deleteFromBacking(K key) {
        return backingStore.remove(key) != null;
    }

    @Override
    public boolean existsInBacking(K key) {
        return backingStore.containsKey(key);
    }
}
```
