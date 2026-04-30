# Distributed Cache System

## Problem Summary

Design a **distributed in-memory cache** (like Redis/Memcached) with multiple eviction policies, consistent hashing for key distribution, replication for fault tolerance, and support for cache-aside, read-through, write-through, and write-behind patterns. The core challenges are **O(1) eviction**, **even key distribution** across nodes, and **handling thundering herds** on cache misses.

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **LRU: HashMap + DoublyLinkedList = O(1).** Most recently used at head, evict from tail.
- **LFU: HashMap + frequency buckets (LinkedHashSet per freq).** Track minFreq. O(1).
- **Consistent Hashing: TreeMap ring + 150 virtual nodes per physical.** Only K/N keys move on rebalance.
- **Cache patterns: Cache-Aside (lazy), Read-Through, Write-Through, Write-Behind**
- **CAP: AP -- stale data OK, downtime is not. Eventual consistency.**
- **Thundering herd: lock on cache miss, only one thread fetches from DB**
- **Hot key: local L1 cache + key replication across nodes**
- **Eviction policies: LRU (most common), LFU (frequency-based), TTL (time-based)**
- **Tiered caching: L1 (local/Caffeine) -> L2 (Redis) -> DB. L1 absorbs 50-80% of reads.**

---

## Class Hierarchy

```
EvictionStrategy (interface)              DistributionStrategy (interface)
  |-- LRUEvictionStrategy                   |-- ConsistentHashStrategy
  |-- LFUEvictionStrategy                   |-- ModuloHashStrategy
  |-- TTLEvictionStrategy
                                          ReplicationStrategy (interface)
CacheStore (interface)                      |-- PrimaryReplicaStrategy
  |-- InMemoryCacheStore                    |-- LeaderlessReplicationStrategy

CacheService (Facade)                     CacheEntry (Builder pattern)
  |-- get(), put(), delete()                |-- key, value, TTL, frequency
  |-- tiered: L1 + L2                       |-- isExpired(), touch()

CacheNode                                VirtualNode
  |-- nodeId, host, port                    |-- physicalNode, replicaIndex, hash
  |-- isHealthy, assignedKeys

AppConfig (wiring)
  |-- creates nodes, strategies, service
```

---

## Key Components

| Component | Role |
|-----------|------|
| `CacheEntry` | Value wrapper with TTL, frequency, timestamps. Builder pattern. |
| `CacheStore` | Interface for storage backend. `InMemoryCacheStore` uses ConcurrentHashMap. |
| `EvictionStrategy` | Interface for eviction. LRU, LFU, TTL implementations. |
| `LRUEvictionStrategy` | HashMap + DoublyLinkedList. O(1) get/put/evict. |
| `LFUEvictionStrategy` | HashMap + frequency buckets (LinkedHashSet per freq). O(1). |
| `ConsistentHashStrategy` | TreeMap ring + 150 virtual nodes. O(log N) key lookup. |
| `CacheNode` | Physical cache server (host, port, health status). |
| `VirtualNode` | Position on hash ring. Maps back to CacheNode. |
| `ReplicationStrategy` | Interface for replicating data across nodes. |
| `CacheService` | Facade. Orchestrates store, eviction, distribution, replication. |
| `AppConfig` | Wires everything together. Creates nodes, strategies, service. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Eviction algorithm | LRU (recency) | LFU (frequency) | **Both via Strategy** -- swap at runtime |
| Key distribution | Modulo hashing | Consistent hashing | **Consistent hashing** -- only K/N keys move on rebalance |
| Virtual nodes count | 50 (fewer) | 200 (more) | **150** -- good balance of distribution vs memory |
| Cache pattern | Cache-Aside | Read/Write-Through | **Cache-Aside** default, supports all via Strategy |
| Consistency model | Strong (CP) | Eventual (AP) | **AP** -- stale cache is fine, downtime is not |
| Replication | Sync (strong) | Async (fast) | **Async** -- lower write latency, accept brief staleness |
| Serialization | JSON (readable) | Binary (fast) | **Binary** in production, JSON for debugging |
| Local cache (L1) | None | Caffeine/Guava | **Yes** -- absorbs 50-80% of reads, reduces Redis load |

---

## SOLID Principles

| Principle | Example |
|-----------|---------|
| **S** -- Single Responsibility | `LRUEvictionStrategy` handles only LRU logic. `ConsistentHashStrategy` handles only key routing. |
| **O** -- Open/Closed | Add `LFUEvictionStrategy` without modifying `CacheService`. New strategy = new class. |
| **L** -- Liskov Substitution | Any `EvictionStrategy` implementation works wherever the interface is expected. |
| **I** -- Interface Segregation | `EvictionStrategy`, `DistributionStrategy`, `ReplicationStrategy` are separate interfaces. |
| **D** -- Dependency Inversion | `CacheService` depends on `EvictionStrategy` interface, not `LRUEvictionStrategy` class. |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** (x3) | EvictionStrategy, DistributionStrategy, ReplicationStrategy | Each dimension varies independently |
| **Facade** | CacheService | Single entry point for get/put/delete operations |
| **Builder** | CacheEntry.Builder | 7 fields -- avoids telescoping constructor, self-documenting |
| **Observer** | Cache metrics / monitoring | Track hit rate, eviction count, latency without coupling |
| **Template Method** | Base eviction flow (check TTL, then apply policy) | Shared expiry check, subclass-specific eviction |
| **Factory** | StrategyFactory creates eviction/distribution strategies | Encapsulate strategy selection logic |
| **Repository** | CacheStore interface | Abstract storage backend, swap in-memory for Redis |
| **Proxy** | L1 local cache wrapping L2 remote cache | Transparent tiered caching |

---

## CAP Summary

```
Choice: AP (Availability + Partition Tolerance)

Why:
- Cache MUST be available (cache miss = DB hit = acceptable, cache DOWN = cascading failure)
- Stale data for a few seconds is tolerable (eventual consistency)
- During network partition: serve stale data from local replica rather than error
- Redis replication is async by default -- confirms AP choice

What we sacrifice:
- Consistency: Replica may serve stale data for 1-2 seconds after a write
- On master failure: last few async writes may be lost (acceptable for cache)
- Two clients may see different values briefly (converges quickly)
```

---

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Language | Java 17+ | Primary implementation |
| Build | Gradle | Build and dependency management |
| L1 Cache | Caffeine / ConcurrentHashMap | Local in-process cache, ~nanosecond access |
| L2 Cache | Redis (production) / InMemoryCacheStore (interview) | Distributed cache, sub-ms access |
| Hashing | TreeMap (consistent hash ring) | O(log N) key-to-node mapping |
| Eviction | DoublyLinkedList + HashMap (LRU), FreqBuckets (LFU) | O(1) eviction operations |
| Serialization | Java serialization / Kryo / Protobuf | Object-to-bytes for network transfer |
| Monitoring | Prometheus + Grafana | Hit rate, evictions, latency, memory |

---

## Common Interview Follow-Up Questions

### 1. How does LRU achieve O(1) for all operations?
HashMap gives O(1) key lookup. DoublyLinkedList gives O(1) move-to-head and remove-from-tail. On `get()`: look up in map, move node to head. On `put()`: add to head, if full, evict tail. On evict: remove tail node, delete from map.

### 2. How does consistent hashing work?
Hash ring (TreeMap) with positions 0 to 2^31. Each physical node gets 150 virtual node positions. To find which node owns a key: hash the key, walk clockwise on the ring (`ceilingEntry`), first virtual node found points to the physical node.

### 3. Why 150 virtual nodes?
Fewer virtual nodes = uneven key distribution (some nodes get 3x more keys). More virtual nodes = more memory for the ring. 150 is the industry standard -- gives <10% variance in key distribution across nodes.

### 4. What happens when a node is added or removed?
Only K/N keys need to move (K = total keys, N = total nodes). The new node takes ownership of keys between it and the previous node on the ring. Compare to modulo hashing where ALL keys must be rehashed.

### 5. How to handle thundering herd on cache miss?
Use a lock (or semaphore) per key. First thread to miss acquires the lock, fetches from DB, populates cache. Other threads wait on the lock, then read from cache. In Java: `ConcurrentHashMap.computeIfAbsent()` or explicit `ReentrantLock` per key.

### 6. How to handle hot keys?
Three approaches: (a) L1 local cache in each app server -- hot keys served from local memory, no Redis round-trip. (b) Replicate hot keys across multiple Redis nodes -- distribute read load. (c) Key splitting -- `user:42` becomes `user:42:0`, `user:42:1`, etc., spread across nodes.

### 7. What is cache-aside vs read-through vs write-through vs write-behind?
- **Cache-aside**: App checks cache, on miss reads DB and populates cache. App owns the logic.
- **Read-through**: Cache checks DB on miss automatically. Cache owns the logic.
- **Write-through**: Write to cache and DB synchronously. Strong consistency, higher write latency.
- **Write-behind**: Write to cache immediately, async flush to DB. Lower latency, risk of data loss.

### 8. LRU vs LFU -- when to use which?
LRU is better for **temporal locality** (recently accessed = likely accessed again). LFU is better for **frequency locality** (frequently accessed = important). LFU has a "frequency stagnation" problem -- old popular items never get evicted even if no longer relevant. Solution: decay frequency over time.

### 9. How to handle cache invalidation?
Three approaches: (a) TTL-based -- keys expire after N seconds. Simple, eventual consistency. (b) Event-based -- on DB write, publish invalidation event (Kafka/Redis Pub/Sub), subscribers delete cached key. (c) Version-based -- key includes version number, bump on write, old version auto-invalidates.

### 10. How does replication work in Redis?
Primary-replica async replication. Primary processes writes, streams write commands to replicas via replication buffer. Replicas apply commands in order. On primary failure, a replica is promoted. Last few async writes may be lost (acceptable for cache).

### 11. What metrics should you monitor for cache health?
Hit rate (target >95%), eviction rate, memory usage, latency p50/p99, connection count, replication lag, keyspace misses, expired key count. Low hit rate = cache is too small or TTLs are too short.

### 12. How to warm a cold cache after deploy/restart?
Three approaches: (a) Lazy warming -- let cache populate organically via cache-aside. Temporary DB spike. (b) Pre-warming -- on startup, load top-N hot keys from DB into cache. (c) Snapshot restore -- Redis RDB snapshot loads previous state on restart.

---

## Quick Reference

```
LRU:               O(1) get, O(1) put, O(1) evict  (HashMap + DoublyLinkedList)
LFU:               O(1) get, O(1) put, O(1) evict  (HashMap + FreqBuckets)
Consistent Hashing: O(log N) key lookup             (TreeMap, N = virtual nodes)
Virtual Nodes:     150 per physical node             (industry standard)
Key movement:      K/N keys on rebalance             (K = total keys, N = nodes)
Cache-Aside:       App manages cache                 (most common pattern)
Write-Through:     Cache manages DB writes           (strong consistency)
Write-Behind:      Async DB writes                   (lower latency, risk of loss)
TTL:               Lazy expiry on access             (no background thread needed)
```

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :07-distributed-cache:run
```

---

## Demo Output Preview

```
========================================
  DISTRIBUTED CACHE SYSTEM DEMO
========================================

--- LRU Eviction Demo (capacity=3) ---
PUT user:1 = "Alice"      --> [user:1]
PUT user:2 = "Bob"        --> [user:1, user:2]
PUT user:3 = "Charlie"    --> [user:1, user:2, user:3]
GET user:1 = "Alice"      --> moves to head: [user:2, user:3, user:1]
PUT user:4 = "Diana"      --> EVICT user:2 (LRU tail): [user:3, user:1, user:4]

--- LFU Eviction Demo (capacity=3) ---
PUT user:1 = "Alice"      freq=1
PUT user:2 = "Bob"        freq=1
GET user:1                 freq=2 (bumped)
GET user:1                 freq=3 (bumped)
PUT user:3 = "Charlie"    freq=1
PUT user:4 = "Diana"      --> EVICT user:2 (minFreq=1, oldest in bucket)

--- Consistent Hashing Demo (3 nodes, 150 vnodes each) ---
Adding node-1 (192.168.1.1:6379) --> 150 virtual nodes on ring
Adding node-2 (192.168.1.2:6379) --> 150 virtual nodes on ring
Adding node-3 (192.168.1.3:6379) --> 150 virtual nodes on ring

Key distribution:
  node-1: 342 keys (34.2%)
  node-2: 328 keys (32.8%)
  node-3: 330 keys (33.0%)

Adding node-4 --> only 248/1000 keys moved (K/N = 25%)

--- TTL Expiry Demo ---
PUT session:abc TTL=2s     --> cached
GET session:abc            --> "session_data" (not expired)
... 3 seconds later ...
GET session:abc            --> null (expired, lazy eviction)

========================================
  DEMO COMPLETE
========================================
```

---

## What to Improve Later

- [ ] Write-behind with async flush to simulated DB
- [ ] Read-through with automatic DB fetch on cache miss
- [ ] Thundering herd protection (singleflight / per-key locking)
- [ ] Hot key detection and local L1 cache promotion
- [ ] Cache warming on startup (pre-load hot keys)
- [ ] Redis Pub/Sub for distributed cache invalidation
- [ ] Memory-aware eviction (evict by size, not just count)
- [ ] Bloom filter for negative cache lookups (avoid DB hit for non-existent keys)
- [ ] Consistent hashing with bounded load (Google's algorithm)
