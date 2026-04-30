# CAP Theorem & Distributed Tradeoffs in the Distributed Cache

> Interview-ready reference for a Senior Java developer.
> A distributed cache is a textbook CAP discussion -- caches almost always choose AP (Availability + Partition Tolerance).
> Stale data is acceptable. Downtime is not.

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| CAP Classification | AP system -- availability + partition tolerance |
| Why AP Over CP | Stale cache is annoying; unavailable cache is catastrophic |
| Consistency Models | Eventual, read-your-writes, monotonic reads |
| Network Partition Behavior | Split brain, stale reads, conflict resolution |
| Replication Tradeoffs | Sync vs async, quorum writes |
| Redis Cluster Deep Dive | AP with tunable consistency |
| Comparison Table | Redis vs Memcached vs our implementation |
| When to Choose CP | Financial data, session tokens, distributed locks |
| Interview Q&A | Ready-to-use answers |

---

## CAP Classification: This Is an AP System

```
         Consistency (C)
            /\
           /  \
          / CP \
         /------\
        /   AP   \  <--- DISTRIBUTED CACHE IS HERE
       /____________\
  Availability (A) --- Partition Tolerance (P)
```

### Why a Cache Chooses AP

| Property | What It Means for Cache | Priority |
|----------|------------------------|----------|
| **Availability (A)** | Every request gets a response (even if data is stale) | HIGH -- cache miss is OK, cache timeout kills latency SLA |
| **Partition Tolerance (P)** | System works despite network splits between nodes | HIGH -- network issues are inevitable in distributed systems |
| **Consistency (C)** | Every read returns the most recent write | LOW -- stale data from cache is acceptable (DB is source of truth) |

### The Fundamental Argument

```
  Scenario: Network partition between Cache Node A and Cache Node B

  OPTION 1: Choose Consistency (CP)
  =================================
  - Node A has "user:1" = {name: "Alice"}
  - Node B receives update: "user:1" = {name: "Alicia"}
  - Network partition occurs
  - Node A REFUSES to serve reads for "user:1" (can't verify consistency)
  - Result: Cache is UNAVAILABLE for that key
  - All requests go to database -- cache becomes useless
  - Database gets hammered -- cascading failure

  OPTION 2: Choose Availability (AP)  <--- CORRECT FOR CACHE
  ===================================
  - Node A has "user:1" = {name: "Alice"}        (stale)
  - Node B has "user:1" = {name: "Alicia"}       (fresh)
  - Network partition occurs
  - Node A serves stale data: {name: "Alice"}
  - Node B serves fresh data: {name: "Alicia"}
  - Result: Both nodes AVAILABLE, one has stale data
  - TTL will eventually expire stale entry
  - No database stampede
```

**The key insight**: A cache is NOT the source of truth. The database is. Serving stale cached data is an inconvenience (user sees old name for 30 seconds). Refusing to serve is a system failure (latency spikes, database overload, potential cascading failure).

---

## Consistency Models for Distributed Cache

### 1. Eventual Consistency (Default for Most Caches)

```
  Writer               Cache Node A           Cache Node B         Reader
    |                       |                       |                 |
    | (1) PUT "user:1"      |                       |                 |
    |   = "Alicia"          |                       |                 |
    |---------------------->|                       |                 |
    |                       | (2) ACK to writer     |                 |
    |<----------------------|                       |                 |
    |                       |                       |                 |
    |                       | (3) async replicate   |                 |
    |                       |---------------------->|                 |
    |                       |                       |                 |
    |                       |                       |    (4) GET      |
    |                       |                       |    "user:1"     |
    |                       |                       |<----------------|
    |                       |                       |                 |
    |                       |                       | (5) return      |
    |                       |                       |   "Alicia"      |
    |                       |                       |---------------->|
    |                       |                       |                 |
    |   TIME WINDOW: between (2) and (3), Node B still has stale data
    |   After (3) completes, both nodes are consistent
```

**Properties:**
- Writes are fast (no waiting for replication)
- Reads might see stale data for a short window
- All nodes converge to the same state eventually
- TTL provides an upper bound on staleness

### 2. Read-Your-Writes Consistency

```
  Client A             Cache Node A           Cache Node B
    |                       |                       |
    | (1) PUT "user:1"      |                       |
    |   = "Alicia"          |                       |
    |---------------------->|                       |
    |                       |                       |
    | (2) GET "user:1"      |                       |
    |---------------------->|                       |
    |                       |                       |
    |<-- "Alicia" ----------|  (same node = sees own write)
    |                       |                       |
    |                       |                       |
  Client B                  |                       |
    |                       |    (3) GET "user:1"   |
    |                       |                       |<--- Client B
    |                       |                       |
    |                       |    "Alice" (stale) ---|---> Client B
    |                       |                       |
    |   Client A always sees its own writes (routed to same node)
    |   Client B might see stale data (different node)
```

**Implementation**: Route reads and writes for the same session/user to the same node (sticky sessions or hash-based routing). Our consistent hashing naturally provides this -- the same key always maps to the same node.

### 3. Monotonic Reads Consistency

```
  Client reads from Node A:  "user:1" = "Alicia"  (version 2)
  Client reads from Node B:  "user:1" = "Alice"   (version 1)  <-- VIOLATION!
  
  Monotonic reads guarantee: once you've seen version 2,
  you will never see version 1 again.
  
  Solution: Pin client to a single node (or track version numbers)
```

---

## What Happens During a Network Partition

### Split Brain Scenario

```
  BEFORE PARTITION:
  =================
  Node A  <----replication---->  Node B  <----replication---->  Node C
  "user:1" = "Alice"            "user:1" = "Alice"             "user:1" = "Alice"
  (all consistent)

  DURING PARTITION:
  =================
  +------ Partition 1 ------+     +------ Partition 2 ------+
  |                         |     |                         |
  |  Node A     Node B      | X X |     Node C              |
  |  "user:1"   "user:1"    |     |     "user:1"            |
  |  = "Alice"  = "Alice"   |     |     = "Alice"           |
  |                         |     |                         |
  |  Client writes:         |     |  Client writes:         |
  |  "user:1" = "Alicia"    |     |  "user:1" = "Ali"       |
  |                         |     |                         |
  |  Node A: "Alicia"       |     |  Node C: "Ali"          |
  |  Node B: "Alicia"       |     |                         |
  |  (replicated within     |     |  (no replication to     |
  |   partition)            |     |   A or B)               |
  +-------------------------+     +-------------------------+

  AFTER PARTITION HEALS:
  ======================
  Node A: "Alicia"  vs  Node C: "Ali"  -- CONFLICT!

  Resolution strategies:
  1. Last-Writer-Wins (LWW): Compare timestamps, higher timestamp wins
  2. Vector Clocks: Track causal ordering, detect true conflicts
  3. Application-Level: Let the application merge (rare for cache)
  4. TTL Expiration: Just wait -- both entries expire, next read fetches from DB
```

### Cache-Specific Advantage: TTL Solves Most Conflicts

Unlike a database, a cache has a built-in conflict resolution mechanism: **TTL expiration**. Even if nodes have conflicting values after a partition heals, the entries will expire within seconds or minutes, and the next cache miss will fetch the correct value from the source of truth (the database).

```
  Partition heals at T=0
  Node A: "user:1" = "Alicia" (TTL expires in 45 seconds)
  Node C: "user:1" = "Ali"    (TTL expires in 30 seconds)

  T=30:  Node C entry expires -> next read fetches from DB -> correct value
  T=45:  Node A entry expires -> next read fetches from DB -> correct value
  T=46:  All nodes converge to correct value. Zero application logic needed.
```

---

## Replication Tradeoffs

### Synchronous vs Asynchronous Replication

```
  SYNCHRONOUS REPLICATION (strong consistency, higher latency)
  ===========================================================

  Client        Primary Node      Replica 1        Replica 2
    |                |                |                |
    | (1) PUT        |                |                |
    |--------------->|                |                |
    |                | (2) write      |                |
    |                | locally        |                |
    |                |                |                |
    |                | (3) replicate  |                |
    |                |--------------->|                |
    |                | (4) replicate  |                |
    |                |-------------------------------->|
    |                |                |                |
    |                |<-- ACK --------|                |
    |                |<-- ACK -------------------------| 
    |                |                |                |
    |<-- ACK --------|  (5) respond only after ALL replicas confirm
    |                |                |                |
    |   Latency: ~5-10ms (network round trips to replicas)
    |   Consistency: STRONG -- all nodes have the data before client gets ACK


  ASYNCHRONOUS REPLICATION (eventual consistency, lower latency)
  ==============================================================

  Client        Primary Node      Replica 1        Replica 2
    |                |                |                |
    | (1) PUT        |                |                |
    |--------------->|                |                |
    |                | (2) write      |                |
    |                | locally        |                |
    |                |                |                |
    |<-- ACK --------|  (3) respond IMMEDIATELY        |
    |                |                |                |
    |                | (4) async      |                |
    |                | replicate      |                |
    |                |--------------->|                |
    |                |-------------------------------->|
    |                |                |                |
    |   Latency: ~1ms (local write only)
    |   Consistency: EVENTUAL -- replicas lag behind
    |   Risk: Data loss if primary crashes before (4)
```

### Comparison Table: Replication Strategies

| Strategy | Latency | Consistency | Data Loss Risk | Use Case |
|----------|---------|-------------|----------------|----------|
| **Async (fire-and-forget)** | ~1ms | Eventual | High (primary crash = data loss) | Session cache, page fragments |
| **Async (buffered)** | ~1ms | Eventual | Medium (buffer survives restarts) | General-purpose cache |
| **Semi-sync (1 replica ACK)** | ~3ms | Read-your-writes | Low | User profile cache |
| **Sync (all replicas ACK)** | ~5-10ms | Strong | None | NOT recommended for cache (defeats the purpose) |
| **Quorum (W + R > N)** | ~3-5ms | Tunable | Low | Configurable consistency |

### Quorum-Based Consistency

```
  N = 3 (total replicas)
  W = 2 (write quorum -- must ACK before responding)
  R = 2 (read quorum -- must read from 2 nodes)

  W + R > N  -->  2 + 2 > 3  -->  TRUE --> Guaranteed overlap

  Write to Node A, B, C:
    Node A: ACK  -----+
    Node B: ACK  -----+-- W=2 met, respond to client
    Node C: (slow, still replicating)

  Read from any 2 nodes:
    At least ONE of the two will have the latest write
    (because W=2 means at least 2 nodes have it)
```

---

## Redis Cluster: AP with Configurable Consistency

### Redis Default Behavior (AP)

```
  Redis Cluster Architecture:
  
  +----------+     +----------+     +----------+
  | Master 1 |     | Master 2 |     | Master 3 |
  | slots     |     | slots     |     | slots     |
  | 0-5460   |     | 5461-10922|     | 10923-16383|
  +----+-----+     +----+-----+     +----+-----+
       |                |                |
       | async          | async          | async
       | replication    | replication    | replication
       |                |                |
  +----+-----+     +----+-----+     +----+-----+
  | Replica 1|     | Replica 2|     | Replica 3|
  +----------+     +----------+     +----------+
  
  - 16384 hash slots distributed across masters
  - Each key hashes to a slot: CRC16(key) % 16384
  - Replication is ASYNCHRONOUS by default
  - On master failure, replica promotes automatically (Sentinel/Cluster)
```

### Redis WAIT Command (Tunable Consistency)

```java
// Default: async replication (AP)
jedis.set("user:1", "Alicia");
// Returns immediately -- replica might not have it yet

// With WAIT: semi-synchronous (moves toward CP)
jedis.set("user:1", "Alicia");
long replicasAcked = jedis.wait(1, 100);  // wait for 1 replica, 100ms timeout
// replicasAcked = number of replicas that confirmed
// If timeout expires, returns 0 -- data might still be only on master
```

### Redis During Partition

```
  Master 1                    Replica 1
  (accepting writes)          (promoted to master during partition)
       |                           |
       | PARTITION                 | PARTITION
       |                           |
  Client A writes here        Client B reads here
  "user:1" = "new value"     "user:1" = "old value"
       |                           |
       | PARTITION HEALS           |
       |                           |
  Redis resolves: Replica 1 was promoted
  Master 1's writes during partition are LOST
  (this is the AP tradeoff)
```

---

## Comparison Table: Redis vs Memcached vs Our Implementation

| Feature | Redis | Memcached | Our Implementation |
|---------|-------|-----------|-------------------|
| **CAP Classification** | AP (tunable) | AP | AP |
| **Consistency** | Eventual (WAIT for semi-sync) | Eventual | Eventual (single-node: strong) |
| **Replication** | Async (configurable) | None (client-side) | None (simulated with NodeAwareCacheStore) |
| **Partition Handling** | Automatic failover | Client routes around | N/A (in-memory) |
| **Data Loss on Failure** | Possible (async window) | Always (no persistence) | Always (in-memory) |
| **Conflict Resolution** | Last-writer-wins | N/A (no replication) | N/A |
| **Eviction** | LRU, LFU, volatile-TTL, etc. | LRU only | LRU, LFU, TTL (Strategy pattern) |
| **Persistence** | RDB + AOF | None | None |
| **Multi-threading** | Single-threaded (I/O threads in 6.0+) | Multi-threaded | Multi-threaded (ConcurrentHashMap) |
| **Max Data Size** | Limited by RAM | Limited by RAM | Limited by JVM heap |
| **Data Structures** | Strings, Lists, Sets, Hashes, Sorted Sets, Streams | Strings only | Strings (Objects) only |
| **Cluster Mode** | Yes (16384 hash slots) | No (client-side sharding) | Simulated (consistent hashing) |
| **Use Case** | General-purpose, sessions, leaderboards, pub/sub | Simple key-value caching | Interview demonstration |

---

## When to Choose CP for Cache

Most caches are AP, but some scenarios demand consistency:

### 1. Distributed Locks

```
  WRONG (AP cache for locks):
  
  Client A         Cache Node 1        Cache Node 2        Client B
    |                    |                   |                  |
    | SET lock:resource  |                   |                  |
    | NX EX 30           |                   |                  |
    |<-- OK (acquired) --|                   |                  |
    |                    |   [PARTITION]      |                  |
    |                    |                   |  SET lock:resource|
    |                    |                   |  NX EX 30        |
    |                    |                   |<-- OK (acquired!)|
    |                    |                   |                  |
    |   BOTH clients think they hold the lock -- MUTUAL EXCLUSION VIOLATED

  CORRECT: Use CP system (RedLock, ZooKeeper, etcd) for distributed locks
```

### 2. Financial Data / Inventory Counts

```
  WRONG (AP cache for inventory):
  
  Product stock = 1 (last item)
  
  Node A cache: stock = 1        Node B cache: stock = 1
  Client A reads Node A: "1 in stock, allow purchase"
  Client B reads Node B: "1 in stock, allow purchase"
  Both purchase -- OVERSOLD by 1 unit
  
  CORRECT: Read inventory from database (CP) not cache
  Or use cache-aside with optimistic locking on DB write
```

### 3. Session Tokens (Security-Critical)

```
  WRONG (AP cache for sessions):
  
  User logs out -> DELETE session from Node A
  Node B still has the session (async replication delay)
  Attacker reads from Node B -> session still valid
  
  CORRECT: Use CP session store, or add session to a revocation list
  that is checked synchronously
```

### Decision Matrix

| Data Type | Consistency Needed | Recommended Approach |
|-----------|-------------------|---------------------|
| Page fragments, HTML | Eventual | AP cache + TTL |
| User profiles | Eventual | AP cache + TTL (30s-5min) |
| Product catalog | Eventual | AP cache + event invalidation |
| Shopping cart | Read-your-writes | Sticky sessions + AP cache |
| Session tokens | Strong | CP store (Redis with WAIT) or DB |
| Inventory counts | Strong | DB read (bypass cache) |
| Distributed locks | Strong | RedLock / ZooKeeper / etcd |
| Financial transactions | Strong | Never cache -- always DB |

---

## Interview Q&A

### Q: "Is your distributed cache CP or AP?"

> "AP. A cache is not the source of truth -- the database is. Serving stale data for a few seconds is acceptable; being unavailable causes cascading failures (database stampede, latency spikes). TTL provides a natural consistency bound -- stale entries expire, and the next miss fetches fresh data from the DB."

### Q: "What happens during a network partition?"

> "Nodes in each partition continue serving reads and accepting writes independently. After the partition heals, we rely on TTL expiration for convergence rather than complex conflict resolution. Since the cache is not the source of truth, losing a few writes during a partition is acceptable -- the database has the canonical data."

### Q: "When would you choose CP for a cache?"

> "For distributed locks, session tokens after logout, or inventory counts where double-spending is possible. In those cases, I'd use Redis with the WAIT command for semi-synchronous replication, or bypass the cache entirely and read from the database."

### Q: "How does consistent hashing help during partitions?"

> "Consistent hashing minimizes key redistribution when nodes join or leave. If Node B goes down, only the keys that hashed to Node B need to be redistributed to Node C (its successor on the ring). The other N-1 nodes are unaffected. Without consistent hashing (mod N), every key would need to be remapped."

### Q: "How do you prevent thundering herd after a partition heals?"

> "When a partition heals and a node comes back, it has stale or missing data. A flood of cache misses would hit the database simultaneously (thundering herd). Solutions: (1) cache warming -- pre-populate the recovered node before sending traffic, (2) request coalescing -- only one thread fetches from DB, others wait, (3) stale-while-revalidate -- serve stale data while refreshing in the background."

---

## Consistency Spectrum

```
  WEAK                                                           STRONG
  |-----|-----------|-------------|-------------|------------|------|
  |     |           |             |             |            |      |
  No    Eventual    Monotonic     Read-your-   Causal      Strict
  guar- consistency reads         writes       consistency  serial-
  antee                                                     izability
  |     |           |             |             |            |      |
  Fire  Redis       Session       Sticky       Raft/Paxos  Single
  and   default     pinning       sessions     consensus   node +
  forget                                                    lock
  |     |           |             |             |            |      |
  <---- FASTER, MORE AVAILABLE ---+--- SLOWER, MORE CONSISTENT --->
  
  Most caches live here: [Eventual --- Read-your-writes]
  Databases live here:   [Causal --- Strict serializability]
```

---

## Real-World Cache Failures: Lessons Learned

### Facebook Memcached Outage (2010)

```
  What happened:
  - Configuration change caused cache invalidation storm
  - Millions of keys invalidated simultaneously
  - Database received ALL traffic (thundering herd at global scale)
  - Database couldn't handle the load -> cascading failure
  
  Root cause: No rate limiting on cache invalidation
  
  Fix: "Lease" mechanism
  (1) On cache miss, cache returns a "lease token"
  (2) Only the holder of the lease can populate the cache
  (3) Other requesters wait or get stale data
  (4) Prevents thundering herd at scale
```

### Redis Split Brain in Production

```
  Timeline:
  T=0:   Master + 2 Replicas, all healthy
  T=1:   Network partition isolates Master from Replicas and Sentinel
  T=2:   Sentinel promotes Replica 1 to new Master
  T=3:   TWO Masters accepting writes (split brain)
  T=4:   Partition heals, old Master demoted to Replica
  T=5:   Old Master's writes during T=1-T=4 are LOST
  
  Impact: ~30 seconds of writes lost
  
  Mitigation:
  - min-replicas-to-write 1 (Master refuses writes if no replicas connected)
  - min-replicas-max-lag 10 (Master refuses writes if replicas lag >10s)
  - Trade: Reduces AP toward CP for critical data
```

### AWS ElastiCache Best Practices

| Practice | Why |
|----------|-----|
| Multi-AZ with auto-failover | Survive AZ failure |
| Read replicas (up to 5) | Scale reads, reduce hot key impact |
| Cluster mode enabled | Scale beyond single node memory |
| Reserved nodes | Cost savings for predictable workloads |
| Encryption at rest + in transit | Compliance (PCI, HIPAA) |
| Slow log monitoring | Detect expensive operations |
| Eviction monitoring (CloudWatch) | Alert before cache degrades |

---

## PACELC: Beyond CAP

CAP only describes behavior during partitions. PACELC extends this to normal operation:

```
  If (Partition):
    Choose A (Availability) or C (Consistency)
  Else (normal operation):
    Choose L (Latency) or C (Consistency)

  PACELC classification:
  
  | System      | During Partition | Normal Operation | Classification |
  |-------------|-----------------|------------------|----------------|
  | Redis       | Choose A        | Choose L         | PA/EL          |
  | Memcached   | Choose A        | Choose L         | PA/EL          |
  | Our Cache   | Choose A        | Choose L         | PA/EL          |
  | ZooKeeper   | Choose C        | Choose C         | PC/EC          |
  | DynamoDB    | Choose A        | Choose L (tunable)| PA/EL         |
  | Cassandra   | Choose A (tunable)| Choose L (tunable)| PA/EL (configurable) |
  
  Cache systems are PA/EL:
  - During partition: remain Available (serve stale data)
  - During normal operation: choose Low Latency over Consistency
  - This is exactly what a cache should be
```

### Interview One-Liner for PACELC

> "CAP only applies during partitions, which are rare. PACELC also covers normal operation. Our cache is PA/EL -- during partitions, we stay available with stale data; during normal operation, we choose low latency over strong consistency. Both align with a cache's purpose: fast reads, even if slightly stale."

---

## Consistent Hashing and Partition Recovery

### Node Failure Recovery Flow

```
  BEFORE FAILURE:
  Ring: [Node A] --- [Node B] --- [Node C]
  Keys: user:1->A   user:2->B   user:3->C

  NODE B FAILS:
  Ring: [Node A] --- [  X  ] --- [Node C]
  
  (1) Consistent hashing detects Node B is gone
  (2) Keys that mapped to Node B now map to Node C (successor)
  (3) user:2 -> cache MISS on Node C -> fetch from DB -> populate Node C
  (4) Nodes A and C: UNAFFECTED (their keys don't move)
  
  Keys remapped: only 1/N (33% for 3 nodes)
  With mod hashing: ALL keys remapped (100%) -> catastrophic miss storm

  NODE B RECOVERS:
  Ring: [Node A] --- [Node B] --- [Node C]
  
  (1) Node B rejoins with EMPTY cache
  (2) Keys that belong to Node B gradually migrate back
  (3) Each key: first request -> miss -> DB -> populate Node B
  (4) After warming period: hit rate normalizes
  
  Optimization: Node C proactively transfers Node B's keys back
  (similar to Redis Cluster slot migration)
```

### Key Redistribution Comparison

```
  3 nodes, adding a 4th node:
  
  MOD HASHING:     hash(key) % 3  ->  hash(key) % 4
                   ~75% of keys remapped (3/4 of keys change bucket)
                   MASSIVE cache miss storm
  
  CONSISTENT HASHING:  Only keys between Node C and new Node D move to D
                        ~25% of keys remapped (1/N)
                        Minimal impact on other nodes
  
  +--------+--------+--------+
  | Nodes  | Mod    | Consistent |
  |        | Remap  | Remap      |
  +--------+--------+--------+
  | 3 -> 4 | 75%    | 25%    |
  | 4 -> 5 | 80%    | 20%    |
  | 9 -> 10| 90%    | 10%    |
  | 99->100| 99%    | 1%     |
  +--------+--------+--------+
  
  At scale, consistent hashing is dramatically better.
```

---

## Summary

| Tradeoff | Our Cache's Choice | Why |
|----------|-------------------|-----|
| CAP | AP | Stale data OK; downtime is not |
| PACELC | PA/EL | Low latency over consistency, even without partitions |
| Replication | Async | Low latency; TTL handles staleness |
| Partition behavior | Continue serving | Refuse = database stampede |
| Conflict resolution | TTL expiration | Cache is not source of truth |
| Consistency model | Eventual (read-your-writes via consistent hashing) | Same key always hits same node |
| Node failure recovery | Consistent hashing reroutes (1/N keys) | Mod hashing would reroute ~all keys |
| When to break AP | Locks, sessions, inventory | Use CP store or bypass cache |
