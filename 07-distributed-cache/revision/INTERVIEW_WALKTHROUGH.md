# Interview Walkthrough -- Distributed Cache System

> **Total time: ~35 minutes. The eviction policy deep dive and consistent hashing are 50% of this interview.**
> This problem tests data structures (LRU, LFU), distributed systems (consistent hashing, replication), and practical engineering (thundering herd, hot keys).

---

## Phase 1: Clarify Requirements (2-3 min)

### Questions to Ask

- "What kind of data are we caching? Small objects (user profiles, sessions) or large blobs (images, files)?"
- "What scale? How many reads/writes per second? How much data?"
- "Consistency requirements? Is it OK to serve stale data for a few seconds?"
- "Do we need persistence, or is this a pure ephemeral cache?"
- "Single data center or multi-region?"
- "Do we need to support different eviction policies?"

### Clarified Scope

```
In scope:  GET/PUT/DELETE, LRU + LFU eviction, consistent hashing, replication, TTL
Out of scope: persistence to disk, multi-region (mention only), transactions, Lua scripting
```

### What This Signals

You don't jump to drawing boxes. You think about **what kind of cache** this is (session cache vs CDN vs DB cache) because the design differs significantly.

**Common follow-up:** "Why does it matter what kind of data we're caching?"

**Answer:** "Small objects (1KB) vs large blobs (1MB) changes the eviction strategy. For small objects, LRU by count is fine. For large objects, we need memory-aware eviction -- evict by total bytes, not item count. It also affects serialization: small objects can use JSON, large objects need binary."

---

## Phase 2: High-Level Architecture (5-7 min)

### What to Say

> "I'll design a distributed in-memory cache with three layers: a local L1 cache in each app server, a distributed L2 cache (like Redis), and the source-of-truth database. The L2 layer uses consistent hashing to distribute keys across cache nodes, with async replication for fault tolerance."

### Draw This Diagram

```
                    ┌─────────────────────────────────────────────────┐
                    │              Client Application                  │
                    └───────────────────┬─────────────────────────────┘
                                        │
                              1. GET key="user:42"
                                        │
                    ┌───────────────────▼─────────────────────────────┐
                    │            CacheService (Facade)                │
                    │                                                  │
                    │  2. Check L1 local cache (ConcurrentHashMap)    │
                    │     HIT? --> return immediately (~ns)            │
                    │     MISS? --> continue to step 3                 │
                    │                                                  │
                    │  3. ConsistentHashStrategy.getNode("user:42")   │
                    │     Hash key --> walk ring --> find node         │
                    │                                                  │
                    │  4. GET from L2 remote cache (target node)      │
                    │     HIT? --> update L1, return (~ms)             │
                    │     MISS? --> continue to step 5                 │
                    │                                                  │
                    │  5. Fetch from Database (source of truth)       │
                    │  6. PUT into L2 cache (cache-aside)             │
                    │  7. PUT into L1 local cache                     │
                    │  8. Return to client                            │
                    └───────────────────┬─────────────────────────────┘
                                        │
             ┌──────────────────────────┼──────────────────────────┐
             │                          │                          │
    ┌────────▼─────────┐   ┌───────────▼────────┐   ┌────────────▼───────┐
    │  Cache Node 1    │   │  Cache Node 2      │   │  Cache Node 3      │
    │  (Primary)       │   │  (Primary)         │   │  (Primary)         │
    │                  │   │                    │   │                    │
    │  Keys: a-f       │   │  Keys: g-r         │   │  Keys: s-z         │
    │  EvictionStrategy│   │  EvictionStrategy  │   │  EvictionStrategy  │
    │  (LRU or LFU)   │   │  (LRU or LFU)     │   │  (LRU or LFU)     │
    │                  │   │                    │   │                    │
    │  Replica ──────────> │  Replica ──────────────>│  Replica           │
    │  (async)         │   │  (async)           │   │  (async)           │
    └──────────────────┘   └────────────────────┘   └────────────────────┘
```

### Components to Name

| Component | Role |
|-----------|------|
| CacheService | Facade -- single entry point for all operations |
| L1 Cache | Local in-process cache (Caffeine/ConcurrentHashMap). Nanosecond access. |
| ConsistentHashStrategy | Maps keys to nodes. TreeMap ring with virtual nodes. |
| Cache Nodes | Physical servers, each with own CacheStore + EvictionStrategy |
| EvictionStrategy | LRU, LFU, or TTL. Pluggable via Strategy pattern. |
| ReplicationStrategy | Async replication from primary to replica nodes. |

### What This Signals

You understand **tiered caching** (L1 + L2), not just a single cache layer. This is a senior-level distinction.

**Common follow-up:** "Why not just use L2 directly? Why bother with L1?"

**Answer:** "L1 absorbs 50-80% of reads for hot keys without a network round-trip. At 100K requests/second, that's 50K-80K Redis round-trips eliminated. L1 is free (in-process memory), and the latency drops from ~1ms (Redis) to ~100ns (local HashMap). The tradeoff is L1 can serve stale data -- we mitigate with short TTLs (10-30 seconds) on L1."

---

## Phase 3: Cache Design Deep Dive (8-10 min)

**This is the core of the interview. Spend the most time here.**

### Part A: LRU Eviction -- O(1) Everything

> "LRU uses a HashMap + DoublyLinkedList. The HashMap gives O(1) lookup by key. The linked list maintains access order -- most recent at head, least recent at tail."

```
HashMap:  { "user:1" --> Node1, "user:2" --> Node2, "user:3" --> Node3 }

DoublyLinkedList (most recent at HEAD):

  HEAD <--> [user:3] <--> [user:1] <--> [user:2] <--> TAIL
             (newest)                     (LRU -- evict this)
```

#### Operations (all O(1)):

```
GET "user:1":
  1. HashMap.get("user:1") --> Node1           O(1)
  2. Remove Node1 from current position        O(1) -- doubly linked
  3. Insert Node1 at HEAD                      O(1)
  4. Return Node1.value

PUT "user:4" (cache full, capacity=3):
  1. Remove TAIL node ("user:2")               O(1) -- eviction
  2. HashMap.remove("user:2")                  O(1)
  3. Create new Node("user:4")
  4. Insert at HEAD                            O(1)
  5. HashMap.put("user:4", newNode)            O(1)

DELETE "user:1":
  1. HashMap.get("user:1") --> Node1           O(1)
  2. Remove Node1 from linked list             O(1)
  3. HashMap.remove("user:1")                  O(1)
```

**Java implementation key line:**

```java
// LinkedHashMap with access-order = true IS an LRU cache
new LinkedHashMap<>(capacity, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry eldest) {
        return size() > capacity;
    }
};
```

**Say:** "In an interview, I'd implement it from scratch with a custom DoublyLinkedList to show I understand the internals. But in production, Java's `LinkedHashMap` with `accessOrder=true` is effectively an LRU cache."

**Common follow-up:** "What about thread safety?"

**Answer:** "Wrap with `Collections.synchronizedMap()` for basic safety, but that's a global lock -- bad for throughput. Better: use `ConcurrentHashMap` for the map + `ReentrantReadWriteLock` for the linked list. Or use Caffeine, which implements a concurrent LRU with a write buffer and periodic drain."

### Part B: LFU Eviction -- O(1) with Frequency Buckets

> "LFU evicts the least frequently used item. The naive approach (min-heap) gives O(log N). The O(1) approach uses HashMap + frequency buckets."

```
HashMap: { "A" --> Entry(freq=3), "B" --> Entry(freq=1), "C" --> Entry(freq=1) }

Frequency Buckets (LinkedHashSet per frequency):
  freq=1: { "B", "C" }    <-- minFreq points here
  freq=3: { "A" }

minFreq = 1
```

#### Operations (all O(1)):

```
GET "A" (current freq=3):
  1. HashMap.get("A") --> Entry                       O(1)
  2. Remove "A" from freq=3 bucket                    O(1) -- LinkedHashSet
  3. Add "A" to freq=4 bucket                         O(1)
  4. Entry.frequency = 4
  5. If freq=3 bucket is now empty AND minFreq==3, increment minFreq

PUT "D" (cache full):
  1. Find minFreq bucket (freq=1)                     O(1)
  2. Remove first item from bucket ("B" -- oldest)    O(1) -- LinkedHashSet iterator
  3. HashMap.remove("B")                              O(1)
  4. Insert "D" with freq=1                           O(1)
  5. minFreq = 1 (new item always has freq=1)
```

**Key insight:** "LinkedHashSet maintains insertion order within each frequency bucket. So when two items have the same frequency, we evict the one that reached that frequency first -- it's been at that frequency the longest."

**Common follow-up:** "LRU vs LFU -- when to choose which?"

**Answer:** "LRU for temporal locality -- web sessions, recently viewed items. LFU for frequency locality -- API rate limiting, frequently accessed config. LFU has a 'pollution' problem: old popular items accumulate high frequency and never get evicted even when they're no longer relevant. Fix: decay frequency over time, or use a windowed LFU."

### Part C: Consistent Hashing -- O(log N)

> "Consistent hashing uses a ring (TreeMap) where both nodes and keys are hashed to positions. To find which node owns a key, hash the key and walk clockwise to the first node."

```
Hash Ring (0 to 2^31):

         Node A (pos 1000)
            |
            v
  0 ----[A]-------[B]----------[C]-------[A]-------[B]---- 2^31
         1000     3500          7200      12000     15000
                  Node B                  (Virtual   (Virtual
                                           node A)    node B)

Key "user:42" hashes to 5000
  --> Walk clockwise --> hits Node C at position 7200
  --> Route to Node C
```

```java
// Core implementation
TreeMap<Integer, VirtualNode> ring = new TreeMap<>();

// Add node: create 150 virtual nodes
for (int i = 0; i < 150; i++) {
    int hash = hash(node.getNodeId() + "#" + i);
    ring.put(hash, new VirtualNode(node, i, hash));
}

// Find node for key
public CacheNode getNode(String key) {
    int hash = hash(key);
    Map.Entry<Integer, VirtualNode> entry = ring.ceilingEntry(hash);
    if (entry == null) entry = ring.firstEntry(); // wrap around
    return entry.getValue().getPhysicalNode();
}
```

**Why 150 virtual nodes?**

```
Virtual nodes per physical | Key distribution variance
3                          | ~40% variance (terrible)
10                         | ~20% variance (poor)
50                         | ~10% variance (okay)
150                        | ~5% variance (good)
500                        | ~2% variance (great, but high memory)
```

**Node addition/removal:**

```
Before: 3 nodes, 1000 keys
  Node A: 340 keys, Node B: 330 keys, Node C: 330 keys

Add Node D:
  Node D takes ~250 keys (K/N = 1000/4 = 250)
  These keys come from the ranges BETWEEN Node D's virtual nodes and their predecessors
  Only 25% of keys move. Compare to modulo hashing: 75% of keys move.

Remove Node B:
  Node B's ~330 keys redistribute to A, C, D (whoever is next clockwise)
  Only 33% of keys move.
```

**Common follow-up:** "What hash function do you use?"

**Answer:** "Not `Object.hashCode()` -- it's not uniformly distributed. Use MurmurHash3 or MD5 (take first 4 bytes as integer). MurmurHash3 is faster and has excellent distribution. In our implementation, we use SHA-256 truncated to 32 bits for simplicity."

---

## Phase 4: Scaling and Edge Cases (5-7 min)

### Thundering Herd Problem

> "When a popular cache key expires, hundreds of threads simultaneously miss the cache and all hit the database. This can overwhelm the DB."

```
Without protection:
  1. Key "product:hot-item" expires in cache
  2. 500 requests arrive simultaneously
  3. All 500 check cache --> MISS
  4. All 500 query database --> DB overwhelmed
  5. All 500 write to cache (redundant writes)

With singleflight / per-key locking:
  1. Key "product:hot-item" expires in cache
  2. 500 requests arrive simultaneously
  3. First request acquires lock for key "product:hot-item"
  4. First request queries DB, writes to cache, releases lock
  5. Remaining 499 requests acquire lock, find cache populated, return cached value
  6. Only 1 DB query instead of 500
```

```java
// Java implementation
private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

public Object getWithSingleflight(String key) {
    // Check cache first
    Object cached = cache.get(key);
    if (cached != null) return cached;

    // Singleflight: only one thread fetches from DB
    CompletableFuture<Object> future = inFlight.computeIfAbsent(key, k ->
        CompletableFuture.supplyAsync(() -> {
            Object value = database.get(k);
            cache.put(k, value);
            inFlight.remove(k);
            return value;
        })
    );
    return future.join(); // all threads wait on same future
}
```

**What this signals:** You know about real-world cache failure modes, not just textbook data structures.

### Hot Key Problem

> "A single key gets so many requests that the single cache node holding it becomes a bottleneck."

```
Solutions (pick based on context):

1. L1 Local Cache (simplest)
   - Hot key cached in every app server's local memory
   - 100 app servers x 1 hot key = 100 copies, but zero Redis hits
   - Tradeoff: stale for L1 TTL duration (10-30 seconds)

2. Key Replication (distributed)
   - Replicate hot key to ALL cache nodes, not just its primary
   - Client randomly picks a node for reads --> distributes load
   - Tradeoff: more memory, invalidation complexity

3. Key Splitting
   - "product:hot" becomes "product:hot:0", "product:hot:1", ..., "product:hot:9"
   - Each shard on a different node
   - Client appends random suffix: key + ":" + random(0, 9)
   - Tradeoff: write to all 10 shards on update
```

**Common follow-up:** "How do you detect hot keys?"

**Answer:** "Two approaches. Proactive: instrument your cache client to count requests per key, flag any key exceeding a threshold (e.g., 1000 req/sec). Reactive: Redis `MONITOR` command or `redis-cli --hotkeys` (uses LFU approximation). In production, use the proactive approach -- MONITOR is too expensive for production traffic."

### Node Failure

```
Node failure sequence:
  1. Health check detects Node B is down (ping fails 3 consecutive times)
  2. ConsistentHashStrategy.removeNode(B) -- remove B's 150 virtual nodes from ring
  3. Keys that were on B now route to the next node clockwise (C or A)
  4. Those keys will be cache misses --> refilled from DB via cache-aside
  5. Replica of B (if exists) can serve reads during transition
  6. When B recovers: addNode(B) back to ring, keys gradually migrate back

Impact:
  - Only K/N keys affected (33% if 3 nodes)
  - Temporary spike in DB reads for those keys
  - No data loss (cache is ephemeral, DB is source of truth)
```

### Cache Stampede on Cold Start

```
Problem: Fresh deployment, cache is empty, ALL requests hit DB

Solutions:
  1. Pre-warming: On startup, load top 10K hot keys from DB into cache
  2. Staggered TTLs: Add random jitter to TTLs to prevent synchronized expiry
     TTL = baseTTL + random(0, baseTTL * 0.1)
  3. Gradual traffic shift: Blue-green deploy, send 10% traffic first, let cache warm
  4. Redis persistence: RDB snapshot + AOF restores previous state on restart
```

---

## Phase 5: Code Walkthrough (5-8 min)

### Key Interfaces

```java
// Strategy pattern -- each dimension is an interface
interface EvictionStrategy {
    void onGet(String key, CacheEntry entry);
    void onPut(String key, CacheEntry entry);
    String evict();  // returns key to evict
}

interface DistributionStrategy {
    CacheNode getNode(String key);
    void addNode(CacheNode node);
    void removeNode(CacheNode node);
}

interface ReplicationStrategy {
    void replicate(String key, CacheEntry entry, CacheNode primary);
    CacheEntry readFromReplica(String key, CacheNode failedPrimary);
}

// Storage abstraction
interface CacheStore {
    CacheEntry get(String key);
    void put(String key, CacheEntry entry);
    void delete(String key);
    int size();
}
```

### CacheService Facade

```java
class CacheService {
    private final CacheStore localCache;   // L1
    private final Map<String, CacheStore> nodeStores;  // L2 per node
    private final EvictionStrategy evictionStrategy;
    private final DistributionStrategy distributionStrategy;

    public Object get(String key) {
        // 1. Check L1
        CacheEntry local = localCache.get(key);
        if (local != null && !local.isExpired()) {
            local.touch();
            return local.getValue();
        }

        // 2. Find node via consistent hashing
        CacheNode node = distributionStrategy.getNode(key);
        CacheStore nodeStore = nodeStores.get(node.getNodeId());

        // 3. Check L2
        CacheEntry remote = nodeStore.get(key);
        if (remote != null && !remote.isExpired()) {
            remote.touch();
            evictionStrategy.onGet(key, remote);
            localCache.put(key, remote);  // promote to L1
            return remote.getValue();
        }

        // 4. Cache miss -- caller fetches from DB
        return null;
    }

    public void put(String key, Object value, long ttlSeconds) {
        CacheEntry entry = CacheEntry.builder()
                .key(key).value(value).ttlSeconds(ttlSeconds).build();

        // 1. Find target node
        CacheNode node = distributionStrategy.getNode(key);
        CacheStore nodeStore = nodeStores.get(node.getNodeId());

        // 2. Check capacity, evict if needed
        if (nodeStore.size() >= maxSize) {
            String evictKey = evictionStrategy.evict();
            nodeStore.delete(evictKey);
        }

        // 3. Store in L2
        nodeStore.put(key, entry);
        evictionStrategy.onPut(key, entry);

        // 4. Store in L1
        localCache.put(key, entry);
    }
}
```

**Say:** "CacheService is the Facade. Clients call `get()` and `put()` -- they don't know about consistent hashing, eviction, or L1/L2 tiering. All that complexity is hidden behind two simple methods."

### CacheEntry Builder

```java
CacheEntry entry = CacheEntry.builder()
    .key("user:42")
    .value(userData)
    .ttlSeconds(300)     // expires in 5 minutes
    .sizeBytes(256L)
    .build();
```

**Say:** "CacheEntry has 7 fields. Builder pattern avoids a 7-parameter constructor where you can't tell which `LocalDateTime` is `createdAt` vs `lastAccessedAt` vs `expiresAt`."

### What This Signals

You write **clean, maintainable code** with clear abstractions. The interviewer sees: Strategy pattern, Facade, Builder, Repository, interface-driven design.

---

## Phase 6: Tradeoffs Discussion (3-5 min)

### LRU vs LFU

| Aspect | LRU | LFU |
|--------|-----|-----|
| Evicts | Least recently used | Least frequently used |
| Data structure | HashMap + DoublyLinkedList | HashMap + FreqBuckets |
| Complexity | O(1) all ops | O(1) all ops |
| Best for | Temporal locality (sessions, recent views) | Frequency locality (configs, popular items) |
| Weakness | Scan pollution (one-time scan evicts hot items) | Frequency stagnation (old popular items never evicted) |
| Fix | No fix needed for most workloads | Decay frequency over time (windowed LFU) |
| Real-world | Redis default, Memcached | Redis `allkeys-lfu` (since Redis 4.0) |

### AP vs CP for Cache

| Aspect | AP (This Design) | CP |
|--------|-------------------|-----|
| On network partition | Serve stale data | Reject reads/writes |
| User experience | Slightly stale, always available | Possibly unavailable, always consistent |
| Use case | Caching (stale is acceptable) | Financial data, inventory counts |
| Redis default | Yes (async replication = AP) | No (needs RedLock or app-level) |

**Say:** "For a cache, AP is almost always correct. The whole point of a cache is to trade consistency for speed. If I need strong consistency, I read from the database, not the cache."

### Redis vs Memcached

| Aspect | Redis | Memcached |
|--------|-------|-----------|
| Data structures | Rich (strings, lists, sets, sorted sets, hashes, streams) | Strings only |
| Persistence | RDB + AOF | None |
| Replication | Built-in primary-replica | None (client-side) |
| Clustering | Redis Cluster (server-side sharding) | Client-side sharding only |
| Threads | Single-threaded command exec (I/O threads in 6+) | Multi-threaded |
| Memory efficiency | Higher overhead per key (~100 bytes metadata) | Lower overhead (~50 bytes) |
| **Choose when** | Need features, persistence, replication | Simple cache, multi-threaded perf, minimal overhead |

**One-liner:** "Redis for everything except 'I literally just need a fast HashMap with zero features.'"

### Cache Patterns Comparison

| Pattern | Who Manages Cache | Consistency | Latency | Complexity |
|---------|-------------------|-------------|---------|------------|
| Cache-Aside | Application | Eventual (app controls) | Low read, moderate write | Low |
| Read-Through | Cache library | Eventual (auto-fill) | Low read | Medium |
| Write-Through | Cache library | Strong (sync write) | Higher write | Medium |
| Write-Behind | Cache library | Eventual (async) | Lowest write | High |

**Say:** "Cache-aside is the most common because the application controls the logic. Read-through and write-through move logic into the cache layer, which is cleaner but less flexible. Write-behind is the most dangerous -- if the cache crashes before flushing to DB, you lose data."

---

## Red Flags (What NOT to Do)

- Saying "just use Redis" without explaining LRU/LFU internals
- Not knowing the O(1) data structures behind LRU and LFU
- Using modulo hashing instead of consistent hashing (then asked "what happens when a node is added?")
- Ignoring thundering herd and hot key problems
- Not mentioning tiered caching (L1 + L2)
- Proposing strong consistency for a cache (contradicts the purpose of caching)
- Not knowing cache-aside vs write-through vs write-behind

## Green Flags (What Interviewers Want to Hear)

- Draw the LRU data structure (HashMap + DoublyLinkedList) and explain O(1) operations
- Explain LFU frequency buckets with minFreq tracking
- Draw the consistent hashing ring with virtual nodes
- Mention "only K/N keys move" on node add/remove
- Proactively bring up thundering herd before asked
- Mention L1 local cache for hot keys
- Know when to use LRU vs LFU (temporal vs frequency locality)
- Say "AP for cache -- stale data is fine, downtime is not"
- Mention cache-aside as the default pattern with clear reasoning

---

## 30-Second Elevator Pitch

> "For a distributed cache, I'd use consistent hashing with 150 virtual nodes per server to distribute keys evenly -- only K/N keys move on rebalance. Each node runs LRU eviction using a HashMap plus DoublyLinkedList for O(1) everything. I'd add an L1 local cache in each app server to absorb 50-80% of hot key reads without a network hop. For fault tolerance, async primary-replica replication with automatic failover. The system is AP -- stale data for a few seconds is acceptable. For thundering herd protection, I use per-key locking so only one thread fetches from DB on a cache miss."

**Time: Under 30 seconds. Covers: consistent hashing, eviction, tiered caching, replication, CAP, thundering herd.**

---

## Phase-by-Phase Timing Cheat Sheet

```
Phase 1:  Clarify Requirements      2-3 min   (ask about data type, scale, consistency)
Phase 2:  High-Level Architecture    5-7 min   (draw L1 + L2 + consistent hashing)
Phase 3:  Cache Design Deep Dive     8-10 min  (LRU, LFU, consistent hashing internals)
Phase 4:  Scaling & Edge Cases       5-7 min   (thundering herd, hot keys, node failure)
Phase 5:  Code Walkthrough           5-8 min   (interfaces, CacheService facade, Builder)
Phase 6:  Tradeoffs Discussion       3-5 min   (LRU vs LFU, AP vs CP, Redis vs Memcached)
───────────────────────────────────────────────
Total:                               ~35 min
```

If short on time, shorten Phase 5 (code) and Phase 6 (tradeoffs). Never skip Phase 3 (data structures) -- that's the core of the interview.
