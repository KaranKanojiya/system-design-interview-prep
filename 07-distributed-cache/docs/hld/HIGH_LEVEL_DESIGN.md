# High-Level Design: Distributed Cache

> Interview-optimized system design document.
> Target: 30-45 minute system design discussion.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Assumptions](#3-assumptions)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [API Design](#6-api-design)
7. [Data Model](#7-data-model)
8. [High-Level Architecture](#8-high-level-architecture)
9. [Component Deep Dive](#9-component-deep-dive)
10. [Eviction Strategies](#10-eviction-strategies)
11. [Consistent Hashing](#11-consistent-hashing)
12. [Cache Patterns](#12-cache-patterns)
13. [Concurrency](#13-concurrency)
14. [Scaling](#14-scaling)
15. [Database Choice](#15-database-choice)
16. [CAP Theorem](#16-cap-theorem)
17. [Cloud Services](#17-cloud-services)
18. [Tradeoffs Summary](#18-tradeoffs-summary)
19. [Interview Talking Points](#19-interview-talking-points)

---

## 1. Problem Statement

Design a **Distributed Cache** that stores key-value data across multiple nodes to provide sub-millisecond reads, reduce database load, and scale horizontally. The cache must handle node failures gracefully, distribute data evenly, and support configurable eviction and expiration policies.

**Why is it needed?**

- Databases are slow for high-throughput, low-latency reads (disk I/O, query parsing, network round-trips).
- Repeatedly fetching the same data from a database wastes resources.
- A distributed cache sits between the application and the database, serving hot data from memory.
- At scale (millions of requests/second), even a fast database becomes a bottleneck without a caching layer.
- Caching is the single most impactful optimization in most distributed systems.

**Core Workflow:**

```
Application needs data for key "user:12345"

(1) Application --GET "user:12345"--> Cache Client
(2) Cache Client --hash("user:12345")--> Consistent Hash Ring --> Node B
(3) Cache Client --GET--> Cache Node B
(4a) Cache HIT:  Node B returns value from memory --> Application uses it
(4b) Cache MISS: Application fetches from Database --> writes to Cache Node B --> uses it
```

### Why This Is Asked in Interviews

This is a **core infrastructure** interview question, rated **Medium-Hard**. It appears frequently at FAANG and top-tier companies because it tests:

| Skill Tested                    | What Interviewers Look For                                    |
|---------------------------------|---------------------------------------------------------------|
| **Distributed Systems**         | Partitioning, replication, consistency, failure handling       |
| **Data Structures**             | HashMap + DoublyLinkedList for LRU, frequency buckets for LFU |
| **Concurrency**                 | Thread-safe reads/writes, lock striping, CAS operations       |
| **Hashing**                     | Consistent hashing, virtual nodes, hash ring mechanics        |
| **Tradeoff Analysis**           | AP vs CP, memory vs hit-rate, complexity vs performance       |
| **System Design Breadth**       | Eviction, expiration, replication, cache patterns, stampede   |
| **Production Awareness**        | Thundering herd, hot keys, cache warming, memory management   |

> **Interview tip**: This question lets you demonstrate depth in data structures (LRU/LFU implementation), distributed systems (consistent hashing, replication), and real-world problem solving (thundering herd, hot keys). It is a favorite because the candidate can go as deep as the interviewer wants.

---

## 2. Scope

### In Scope

| Feature                    | Description                                                  |
|----------------------------|--------------------------------------------------------------|
| Key-Value Storage          | Store arbitrary key-value pairs in memory                    |
| Distributed Partitioning   | Spread data across multiple nodes via consistent hashing     |
| Eviction Policies          | LRU, LFU, TTL-based expiration                              |
| Replication                | Master-replica model for high availability                   |
| Cache Patterns             | Cache-aside, read-through, write-through, write-behind       |
| Concurrency                | Thread-safe operations with lock striping                    |
| Horizontal Scaling         | Add/remove nodes with minimal data movement                  |
| Health Monitoring          | Detect and handle node failures                              |
| TTL / Expiration           | Per-key time-to-live with lazy + active cleanup              |

### Out of Scope

| Feature                    | Reason                                                       |
|----------------------------|--------------------------------------------------------------|
| Pub/Sub messaging          | Separate concern (Redis Pub/Sub is an extension, not core)   |
| Lua scripting / stored procs | Implementation detail, not core distributed cache design   |
| Persistence to disk        | Cache is ephemeral by design; persistence is optional add-on |
| Multi-data-center replication | Adds complexity beyond core interview scope               |
| Authentication / ACLs      | Security layer, not core to cache architecture               |
| Data type richness         | Lists, sets, sorted sets are extensions; focus on key-value  |

---

## 3. Assumptions

### Cluster Sizing

| Parameter                  | Value                    |
|----------------------------|--------------------------|
| Number of cache nodes      | 5 (initial cluster)      |
| Memory per node            | 64 GB RAM                |
| Total cache capacity       | 320 GB usable            |
| Virtual nodes per physical | 150                      |
| Replication factor         | 2 (1 master + 1 replica) |

### Traffic

| Parameter                  | Value                    |
|----------------------------|--------------------------|
| Read requests/sec          | 500,000                  |
| Write requests/sec         | 50,000                   |
| Read:Write ratio           | 10:1                     |
| Average key size           | 64 bytes                 |
| Average value size         | 1 KB                     |
| P99 read latency target    | < 1 ms                   |
| P99 write latency target   | < 2 ms                   |
| Cache hit rate target      | > 95%                    |

### Data Characteristics

| Parameter                  | Value                    |
|----------------------------|--------------------------|
| Total unique keys          | ~200 million             |
| Hot key percentage         | 20% of keys serve 80% of traffic (Pareto) |
| Default TTL                | 1 hour                   |
| Max key size               | 256 bytes                |
| Max value size             | 1 MB                     |

### Back-of-the-Envelope Storage

```
200M keys * (64 bytes key + 1 KB value + 100 bytes metadata) = ~230 GB
With replication factor 2: ~460 GB total across cluster
5 nodes * 64 GB = 320 GB per replica set --> fits comfortably
```

---

## 4. Functional Requirements

### FR-1: Put (Write)
Store a key-value pair in the cache. If the key already exists, overwrite it. Optionally accept a TTL.

### FR-2: Get (Read)
Retrieve the value associated with a key. Return null/miss if the key does not exist or has expired.

### FR-3: Delete
Remove a key-value pair from the cache immediately.

### FR-4: TTL / Expiration
Support per-key TTL. Expired keys must not be returned on reads. Expired keys must be cleaned up to reclaim memory.

### FR-5: Eviction
When a node reaches its memory limit, evict entries according to the configured eviction policy (LRU, LFU, or random).

### FR-6: Bulk Operations
Support multi-get and multi-put for batch operations to reduce network round-trips.

### FR-7: Key Existence Check
Check whether a key exists without retrieving the full value (lightweight probe).

### FR-8: Atomic Operations
Support compare-and-swap (CAS) for optimistic concurrency on individual keys.

---

## 5. Non-Functional Requirements

| Requirement            | Target                        | Rationale                                              |
|------------------------|-------------------------------|--------------------------------------------------------|
| **Read Latency**       | < 1 ms (p99)                  | Cache exists to be fast; must beat DB by 10-100x       |
| **Write Latency**      | < 2 ms (p99)                  | Writes may involve replication, slightly slower         |
| **Availability**       | 99.99% (52 min/year)          | Cache failure cascades to DB, causing system-wide issues|
| **Throughput**         | 500K reads/sec, 50K writes/sec| Must handle peak traffic without degradation            |
| **Scalability**        | Linear horizontal scaling     | Adding a node should increase capacity proportionally   |
| **Data Distribution**  | < 10% variance across nodes   | Consistent hashing with vnodes ensures even spread      |
| **Fault Tolerance**    | Survive single-node failure   | Replica promotes automatically, no data loss            |
| **Memory Efficiency**  | < 15% overhead per entry      | Metadata (TTL, LRU pointers) should not dominate        |
| **Hit Rate**           | > 95%                         | Below this, cache is not providing enough value         |

---

## 6. API Design

### 6.1 Put (Write Entry)

```
PUT /api/v1/cache/{key}
Content-Type: application/json
```

**Request:**

```json
{
  "value": "serialized-value-bytes-or-json",
  "ttl_seconds": 3600,
  "if_not_exists": false
}
```

**Response (200 OK):**

```json
{
  "key": "user:12345",
  "status": "STORED",
  "ttl_seconds": 3600,
  "node": "cache-node-03",
  "timestamp": "2026-04-26T10:30:00Z"
}
```

**Response (409 Conflict -- if `if_not_exists: true` and key exists):**

```json
{
  "key": "user:12345",
  "status": "EXISTS",
  "message": "Key already exists. Use PUT without if_not_exists to overwrite."
}
```

### 6.2 Get (Read Entry)

```
GET /api/v1/cache/{key}
```

**Response (200 OK -- Cache Hit):**

```json
{
  "key": "user:12345",
  "value": "serialized-value-bytes-or-json",
  "ttl_remaining_seconds": 2400,
  "node": "cache-node-03",
  "timestamp": "2026-04-26T10:30:00Z"
}
```

**Response (404 Not Found -- Cache Miss):**

```json
{
  "key": "user:12345",
  "status": "MISS",
  "message": "Key not found or expired."
}
```

### 6.3 Delete

```
DELETE /api/v1/cache/{key}
```

**Response (200 OK):**

```json
{
  "key": "user:12345",
  "status": "DELETED"
}
```

**Response (404 Not Found):**

```json
{
  "key": "user:12345",
  "status": "NOT_FOUND",
  "message": "Key does not exist."
}
```

### 6.4 Bulk Get

```
POST /api/v1/cache/bulk-get
Content-Type: application/json
```

**Request:**

```json
{
  "keys": ["user:12345", "user:67890", "product:111"]
}
```

**Response (200 OK):**

```json
{
  "results": {
    "user:12345": { "value": "...", "status": "HIT" },
    "user:67890": { "value": "...", "status": "HIT" },
    "product:111": { "value": null, "status": "MISS" }
  },
  "hits": 2,
  "misses": 1
}
```

### 6.5 Compare-And-Swap (CAS)

```
PUT /api/v1/cache/{key}/cas
Content-Type: application/json
```

**Request:**

```json
{
  "expected_value": "old-value",
  "new_value": "new-value",
  "ttl_seconds": 3600
}
```

**Response (200 OK):**

```json
{
  "key": "user:12345",
  "status": "SWAPPED"
}
```

**Response (409 Conflict):**

```json
{
  "key": "user:12345",
  "status": "CAS_CONFLICT",
  "message": "Current value does not match expected value."
}
```

### 6.6 Cluster Health

```
GET /api/v1/cache/cluster/health
```

**Response (200 OK):**

```json
{
  "status": "HEALTHY",
  "nodes": [
    { "id": "node-01", "status": "UP", "memory_used_pct": 72, "keys": 41200000 },
    { "id": "node-02", "status": "UP", "memory_used_pct": 68, "keys": 39800000 },
    { "id": "node-03", "status": "UP", "memory_used_pct": 75, "keys": 42100000 },
    { "id": "node-04", "status": "UP", "memory_used_pct": 70, "keys": 40500000 },
    { "id": "node-05", "status": "DOWN", "memory_used_pct": 0, "keys": 0 }
  ],
  "total_keys": 163600000,
  "cluster_memory_used_pct": 71,
  "replication_lag_ms": 3
}
```

> **Interview Note:** In practice, cache clients use a binary protocol (like Redis RESP or Memcached binary protocol) for performance. The REST API here is for illustration. Real-world cache operations are single-command TCP calls, not HTTP.

---

## 7. Data Model

### 7.1 Cache Entry Structure

Each cache entry stored in memory has the following structure:

```
+------------------------------------------------------------------+
|                       CacheEntry                                  |
+------------------------------------------------------------------+
| key:         String          (max 256 bytes)                      |
| value:       byte[]          (max 1 MB, serialized)               |
| created_at:  long            (epoch millis, 8 bytes)              |
| ttl_ms:      long            (time-to-live in millis, 8 bytes)    |
| last_access: long            (for LRU, epoch millis, 8 bytes)     |
| frequency:   int             (for LFU, access count, 4 bytes)     |
| version:     long            (for CAS operations, 8 bytes)        |
| size_bytes:  int             (key + value size for memory tracking)|
| prev:        CacheEntry*     (doubly-linked list pointer for LRU) |
| next:        CacheEntry*     (doubly-linked list pointer for LRU) |
+------------------------------------------------------------------+
```

**Memory overhead per entry:**

```
Metadata:  8 + 8 + 8 + 4 + 8 + 4 + 16 (pointers) = ~56 bytes overhead
Key (avg): 64 bytes
Value (avg): 1024 bytes
Total per entry: ~1,144 bytes

Overhead percentage: 56 / 1144 = ~4.9% (well within 15% target)
```

### 7.2 Node Metadata

```
+------------------------------------------------------------------+
|                       CacheNode                                   |
+------------------------------------------------------------------+
| node_id:       String        (unique identifier, e.g., "node-01") |
| host:          String        (IP address or hostname)             |
| port:          int           (listening port)                     |
| status:        enum          (UP, DOWN, DRAINING)                 |
| max_memory:    long          (max heap allocated for cache)       |
| used_memory:   long          (current memory usage)               |
| key_count:     long          (number of entries)                  |
| virtual_nodes: List<Integer> (positions on the hash ring)         |
| role:          enum          (MASTER, REPLICA)                    |
| master_id:     String        (if replica, who is the master)      |
| last_heartbeat: long         (epoch millis of last health check)  |
+------------------------------------------------------------------+
```

### 7.3 Hash Ring Entry

```
+------------------------------------------------------------------+
|                       HashRingEntry                                |
+------------------------------------------------------------------+
| ring_position: int           (hash value, 0 to 2^31 - 1)         |
| physical_node: String        (node_id of the physical node)       |
| virtual_id:    int           (virtual node index, 0 to 149)       |
+------------------------------------------------------------------+
```

### 7.4 Client-Side Configuration

```
+------------------------------------------------------------------+
|                       CacheClientConfig                           |
+------------------------------------------------------------------+
| cluster_nodes:    List<String>   (seed node addresses)            |
| read_from:        enum           (MASTER, REPLICA, NEAREST)       |
| connection_pool:  int            (connections per node, default 8) |
| timeout_ms:       int            (request timeout, default 100)   |
| retry_count:      int            (retries on failure, default 2)  |
| serializer:       enum           (JSON, PROTOBUF, KRYO)           |
| hash_function:    enum           (MURMUR3, XXH3, MD5)             |
+------------------------------------------------------------------+
```

---

## 8. High-Level Architecture

```
+----------+    +----------+    +----------+
|  App     |    |  App     |    |  App     |
| Server 1 |    | Server 2 |    | Server N |
+----+-----+    +----+-----+    +----+-----+
     |               |               |
     +-------+-------+-------+-------+
             |               |
             v               v
     +-------+-------+-------+-------+
     |          Cache Client Library          |
     |  (Consistent Hashing + Connection Pool)|
     +----+---------+---------+---------+----+
          |         |         |         |
          v         v         v         v
     +----+--+ +---+---+ +---+---+ +---+---+ +-------+
     |Node 01| |Node 02| |Node 03| |Node 04| |Node 05|
     |MASTER | |MASTER | |MASTER | |MASTER | |MASTER |
     |64GB   | |64GB   | |64GB   | |64GB   | |64GB   |
     +---+---+ +---+---+ +---+---+ +---+---+ +---+---+
         |         |         |         |         |
         v         v         v         v         v
     +---+---+ +---+---+ +---+---+ +---+---+ +---+---+
     |Node 01| |Node 02| |Node 03| |Node 04| |Node 05|
     |REPLICA| |REPLICA| |REPLICA| |REPLICA| |REPLICA|
     +-------+ +-------+ +-------+ +-------+ +-------+
                                                  |
                                          (on cache miss)
                                                  v
                                          +-------+-------+
                                          |   Database     |
                                          | (Source of     |
                                          |  Truth)        |
                                          +----------------+
```

### Request Flow: Cache Read (GET)

```
(1) App Server calls cacheClient.get("user:12345")
(2) Cache Client computes hash: murmur3("user:12345") = 0x7A3F...
(3) Cache Client walks the hash ring clockwise to find Node 03
(4) Cache Client sends GET request to Node 03 (MASTER) over TCP
(5) Node 03 looks up "user:12345" in its local HashMap
(6a) HIT: Node 03 returns value, updates LRU position --> App Server receives data
(6b) MISS: Node 03 returns MISS
(7) [On MISS] App Server queries Database for user:12345
(8) [On MISS] App Server calls cacheClient.put("user:12345", value, ttl=3600)
(9) [On MISS] Cache Client sends PUT to Node 03
(10) [On MISS] Node 03 stores entry, replicates to Node 03 REPLICA
```

```
+----------+        +-------------+        +---------+        +----------+
|   App    |        |  Cache      |        | Cache   |        | Database |
|  Server  |        |  Client     |        | Node 03 |        |          |
+----+-----+        +------+------+        +----+----+        +-----+----+
     |                      |                    |                   |
     | (1) get("user:12345")|                    |                   |
     +--------------------->|                    |                   |
     |                      | (2) hash(key)      |                   |
     |                      |  = ring pos 0x7A3F |                   |
     |                      |                    |                   |
     |                      | (3) ring lookup    |                   |
     |                      |  --> Node 03       |                   |
     |                      |                    |                   |
     |                      | (4) TCP GET ------>|                   |
     |                      |                    | (5) HashMap       |
     |                      |                    |     lookup        |
     |                      |                    |                   |
     |                      | (6a) HIT: value <--|                   |
     | <-- value -----------|                    |                   |
     |                      |                    |                   |
     |  --- OR ON MISS ---  |                    |                   |
     |                      | (6b) MISS <--------|                   |
     | (7) query DB --------|--------------------|------------------->|
     | <-- DB result -------|--------------------+-------------------|
     |                      |                    |                   |
     | (8) put(key, value)  |                    |                   |
     +--------------------->|                    |                   |
     |                      | (9) TCP PUT ------>|                   |
     |                      |                    | (10) store +      |
     |                      |                    |  replicate        |
     |                      | <-- OK ------------|                   |
     | <-- OK --------------|                    |                   |
     +                      +                    +                   +
```

### Request Flow: Cache Write (PUT)

```
(1) App Server calls cacheClient.put("product:999", value, ttl=1800)
(2) Cache Client computes hash: murmur3("product:999") = 0x2B1C...
(3) Cache Client walks the hash ring clockwise to find Node 01
(4) Cache Client sends PUT request to Node 01 (MASTER)
(5) Node 01 checks memory usage against maxmemory limit
(6a) Under limit: store entry in HashMap, insert at head of LRU list
(6b) Over limit: run eviction policy (evict LRU tail), then store
(7) Node 01 asynchronously replicates to Node 01 REPLICA
(8) Node 01 returns OK to Cache Client
(9) Cache Client returns success to App Server
```

### Request Flow: Node Failure and Recovery

```
(1) Node 03 MASTER crashes (process dies or network partition)
(2) Health checker detects missing heartbeat after 3 consecutive failures (15 sec)
(3) Cluster manager marks Node 03 MASTER as DOWN
(4) Node 03 REPLICA is promoted to MASTER role
(5) Hash ring is updated: Node 03's virtual nodes now point to the promoted replica
(6) Cache Client receives cluster topology update
(7) New requests for Node 03's key range go to the promoted replica
(8) A new REPLICA is provisioned and begins syncing from the new MASTER

Timeline:
  t=0s   Node 03 crashes
  t=5s   First missed heartbeat
  t=10s  Second missed heartbeat
  t=15s  Third missed heartbeat --> failover triggered
  t=16s  Replica promoted, ring updated
  t=17s  Clients updated, traffic re-routed
  Total downtime: ~17 seconds (requests during this window retry on replica)
```

---

## 9. Component Deep Dive

### 9.1 Cache Client Library

The cache client is a lightweight library embedded in each application server. It is responsible for routing requests to the correct cache node.

**Responsibilities:**

- Maintain a local copy of the consistent hash ring
- Route `GET`/`PUT`/`DELETE` to the correct node based on key hash
- Manage a connection pool to each cache node (default: 8 connections per node)
- Handle retries and failover on node failure
- Serialize/deserialize values (JSON, Protobuf, or Kryo)
- Receive cluster topology updates (push or pull)

**Connection Pool Design:**

```
+----------------------------------------------------------+
|                    Cache Client                           |
+----------------------------------------------------------+
|                                                          |
|  Hash Ring (local copy)                                  |
|  +----------------------------------------------------+ |
|  | vnode:0x0012 -> Node01 | vnode:0x0034 -> Node03 |  | |
|  | vnode:0x0056 -> Node02 | vnode:0x0078 -> Node05 |  | |
|  | ... (750 vnodes total = 5 nodes * 150 vnodes)    |  | |
|  +----------------------------------------------------+ |
|                                                          |
|  Connection Pools                                        |
|  +----------------+  +----------------+                  |
|  | Node 01 Pool   |  | Node 02 Pool   |  ...            |
|  | [conn1] [conn2]|  | [conn1] [conn2]|                 |
|  | [conn3] [conn4]|  | [conn3] [conn4]|                 |
|  | [conn5] [conn6]|  | [conn5] [conn6]|                 |
|  | [conn7] [conn8]|  | [conn7] [conn8]|                 |
|  +----------------+  +----------------+                  |
+----------------------------------------------------------+
```

**Key routing pseudocode:**

```
function get(key):
    (1) hash = murmur3(key)               // O(1) hash computation
    (2) node = ring.getNodeFor(hash)       // O(log N) binary search on sorted ring
    (3) conn = pool.borrow(node)           // O(1) borrow from pool
    (4) result = conn.sendGet(key)         // network call, < 1ms typically
    (5) pool.release(conn)                 // return connection to pool
    (6) return result
```

### 9.2 Cache Node (Server)

Each cache node is a standalone process that manages an in-memory key-value store.

**Internal Structure of a Cache Node:**

```
+------------------------------------------------------------------+
|                        Cache Node                                 |
+------------------------------------------------------------------+
|                                                                  |
|  +-------------------+    +----------------------------------+   |
|  |   Network Layer   |    |        Memory Manager            |   |
|  | (TCP Server,      |    | (tracks used/max memory,         |   |
|  |  NIO/epoll)       |    |  triggers eviction when full)    |   |
|  +--------+----------+    +----------------+-----------------+   |
|           |                                |                     |
|           v                                v                     |
|  +--------+------------------------------------------+           |
|  |            Storage Engine                         |           |
|  |                                                   |           |
|  |  HashMap<String, CacheEntry>                      |           |
|  |  +-----+-----+-----+-----+-----+-----+-----+    |           |
|  |  |  0  |  1  |  2  | ... | ... | N-2 | N-1 |    |           |
|  |  +--+--+--+--+--+--+-----+-----+--+--+--+--+    |           |
|  |     |     |     |                  |     |        |           |
|  |     v     v     v                  v     v        |           |
|  |   [Entry] [Entry] [Entry]     [Entry] [Entry]    |           |
|  |                                                   |           |
|  +---------------------------------------------------+           |
|                                                                  |
|  +---------------------------------------------------+           |
|  |     Eviction Policy (pluggable)                   |           |
|  |     - LRU: DoublyLinkedList (head=MRU, tail=LRU)  |           |
|  |     - LFU: FrequencyBuckets (min-freq pointer)     |           |
|  |     - TTL: sorted expiry queue                     |           |
|  +---------------------------------------------------+           |
|                                                                  |
|  +---------------------------------------------------+           |
|  |     Replication Manager                           |           |
|  |     - Async replication to replica node           |           |
|  |     - Replication lag tracking                    |           |
|  +---------------------------------------------------+           |
|                                                                  |
|  +---------------------------------------------------+           |
|  |     TTL Cleanup (background thread)               |           |
|  |     - Runs every 100ms                            |           |
|  |     - Samples 20 keys, deletes expired ones       |           |
|  |     - If > 25% expired, repeat immediately        |           |
|  +---------------------------------------------------+           |
+------------------------------------------------------------------+
```

### 9.3 Consistent Hashing Ring

Covered in depth in [Section 11](#11-consistent-hashing). Summary:

- The hash ring maps each key to a specific cache node.
- Each physical node gets 150 virtual nodes for even distribution.
- When a node is added or removed, only `K/N` keys need to move (K = total keys, N = total nodes).
- The ring is stored in a sorted array for O(log N) lookups via binary search.

### 9.4 Replication Manager

**Master-Replica Model:**

```
                   Writes                    Reads (optional)
                     |                           |
                     v                           v
              +------+------+             +------+------+
              |   MASTER    |   async     |   REPLICA   |
              |   Node 01   +------------>+   Node 01   |
              |             |  replicate  |             |
              +-------------+             +-------------+
```

**Replication Flow:**

```
(1) Client sends PUT("key", "value") to Master
(2) Master stores entry in its local HashMap
(3) Master appends operation to replication log (WAL-like)
(4) Master returns OK to Client (async replication -- do not wait)
(5) Background replication thread sends operation to Replica
(6) Replica applies operation to its local HashMap
(7) Replica acknowledges back to Master
(8) Master updates replication lag metric
```

**Replication Modes:**

| Mode             | How It Works                          | Latency Impact | Durability        |
|------------------|---------------------------------------|----------------|-------------------|
| **Async**        | Master returns before replica confirms | None           | Risk of data loss on master failure |
| **Semi-Sync**    | Master waits for 1 replica ACK        | +1-2ms         | Minimal data loss |
| **Sync**         | Master waits for ALL replica ACKs     | +5-10ms        | No data loss      |

> **Interview Recommendation:** Use **async replication** for a cache. Data loss on master failure is acceptable because the database is the source of truth. The cache can be repopulated. Latency matters more than durability for a cache.

---

## 10. Eviction Strategies

When a cache node reaches its memory limit (`maxmemory`), it must evict entries to make room for new ones. The eviction policy determines which entries are removed.

### 10.1 LRU (Least Recently Used)

**Concept:** Evict the entry that has not been accessed for the longest time.

**Data Structure: HashMap + DoublyLinkedList = O(1) for all operations**

```
HashMap: key --> Node reference (O(1) lookup)
DoublyLinkedList: maintains access order (head = most recent, tail = least recent)

+---------------------------------------------+
|              HashMap                        |
|  "user:1" --> [Node A]                      |
|  "user:2" --> [Node B]                      |
|  "user:3" --> [Node C]                      |
|  "user:4" --> [Node D]                      |
+---------------------------------------------+

DoublyLinkedList (MRU <--> LRU):

  HEAD                                              TAIL
  (MRU)                                             (LRU)
   |                                                  |
   v                                                  v
+------+    +------+    +------+    +------+    +------+
|Node A|<-->|Node C|<-->|Node D|<-->|Node B|<-->| EVICT|
| u:1  |    | u:3  |    | u:4  |    | u:2  |    | next |
+------+    +------+    +------+    +------+    +------+
```

**Operations (all O(1)):**

```
GET "user:3":
  (1) HashMap lookup: "user:3" --> Node C         O(1)
  (2) Remove Node C from its current position     O(1) -- doubly-linked
  (3) Move Node C to HEAD (most recently used)    O(1)
  (4) Return value

PUT "user:5" (capacity full):
  (1) Evict TAIL node (Node B, "user:2")          O(1)
  (2) Remove "user:2" from HashMap                O(1)
  (3) Create new Node E for "user:5"              O(1)
  (4) Insert Node E at HEAD                       O(1)
  (5) Add "user:5" --> Node E in HashMap          O(1)
```

**Java Implementation Sketch (Interview-Ready):**

```java
class LRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;        // O(1) lookup
    private final Node<K, V> head, tail;          // sentinel nodes

    // GET: O(1)
    V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) return null;
        moveToHead(node);                         // mark as recently used
        return node.value;
    }

    // PUT: O(1)
    void put(K key, V value) {
        if (map.containsKey(key)) {
            Node<K, V> node = map.get(key);
            node.value = value;
            moveToHead(node);
        } else {
            if (map.size() >= capacity) {
                Node<K, V> lru = removeTail();    // evict LRU
                map.remove(lru.key);
            }
            Node<K, V> newNode = new Node<>(key, value);
            addToHead(newNode);
            map.put(key, newNode);
        }
    }
}
```

**Pros and Cons of LRU:**

| Aspect         | Assessment                                                     |
|----------------|----------------------------------------------------------------|
| **Pros**       | Simple to implement, O(1) all operations, good general-purpose |
| **Cons**       | Scan pollution: a one-time full scan evicts hot entries         |
| **Best For**   | General workloads with temporal locality                       |
| **Worst For**  | Workloads with periodic full scans (e.g., batch jobs)          |

### 10.2 LFU (Least Frequently Used)

**Concept:** Evict the entry that has been accessed the fewest times. Ties broken by recency.

**Data Structure: HashMap + Frequency Buckets (LinkedHashSet per frequency) = O(1)**

```
HashMap: key --> Node reference (O(1) lookup)
FreqMap: frequency --> LinkedHashSet of nodes at that frequency
minFreq: pointer to the current minimum frequency

+---------------------------------------------+
|              HashMap                        |
|  "user:1" --> [Node A, freq=5]              |
|  "user:2" --> [Node B, freq=1]   <-- LFU   |
|  "user:3" --> [Node C, freq=3]              |
|  "user:4" --> [Node D, freq=1]   <-- LFU   |
+---------------------------------------------+

FreqMap:
  freq=1: { Node B ("user:2"), Node D ("user:4") }  <-- minFreq points here
  freq=3: { Node C ("user:3") }
  freq=5: { Node A ("user:1") }

  minFreq = 1

On eviction: remove the FIRST entry in freq=1 bucket (Node B, "user:2")
             because it is the oldest among freq=1 entries (LRU tiebreak)
```

**Operations (all O(1)):**

```
GET "user:4":
  (1) HashMap lookup: "user:4" --> Node D, freq=1     O(1)
  (2) Remove Node D from freq=1 bucket                O(1)
  (3) Increment Node D's frequency to 2               O(1)
  (4) Add Node D to freq=2 bucket                     O(1)
  (5) If freq=1 bucket is now empty and minFreq==1,
      increment minFreq to 2                           O(1)
  (6) Return value

PUT "user:5" (capacity full):
  (1) Go to FreqMap[minFreq] bucket                    O(1)
  (2) Remove the first (oldest) entry from that bucket O(1)
  (3) Remove evicted key from HashMap                  O(1)
  (4) Create Node E for "user:5" with freq=1           O(1)
  (5) Add to freq=1 bucket and HashMap                 O(1)
  (6) Set minFreq = 1                                  O(1)
```

**Java Implementation Sketch (Interview-Ready):**

```java
class LFUCache<K, V> {
    private final int capacity;
    private int minFreq;
    private final Map<K, Node<K, V>> keyMap;           // key -> node
    private final Map<Integer, LinkedHashSet<K>> freqMap; // freq -> keys (insertion order)

    V get(K key) {
        Node<K, V> node = keyMap.get(key);
        if (node == null) return null;
        updateFrequency(node);
        return node.value;
    }

    void put(K key, V value) {
        if (keyMap.containsKey(key)) {
            Node<K, V> node = keyMap.get(key);
            node.value = value;
            updateFrequency(node);
        } else {
            if (keyMap.size() >= capacity) {
                // Evict from minFreq bucket (first = oldest at that freq)
                LinkedHashSet<K> minBucket = freqMap.get(minFreq);
                K evictKey = minBucket.iterator().next();
                minBucket.remove(evictKey);
                keyMap.remove(evictKey);
            }
            Node<K, V> newNode = new Node<>(key, value, 1);
            keyMap.put(key, newNode);
            freqMap.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);
            minFreq = 1;
        }
    }

    private void updateFrequency(Node<K, V> node) {
        int oldFreq = node.freq;
        freqMap.get(oldFreq).remove(node.key);
        if (freqMap.get(oldFreq).isEmpty() && oldFreq == minFreq) {
            minFreq++;
        }
        node.freq++;
        freqMap.computeIfAbsent(node.freq, k -> new LinkedHashSet<>()).add(node.key);
    }
}
```

**Pros and Cons of LFU:**

| Aspect         | Assessment                                                           |
|----------------|----------------------------------------------------------------------|
| **Pros**       | Resistant to scan pollution, keeps truly hot data                    |
| **Cons**       | Frequency accumulation: old-but-once-hot entries stay forever        |
| **Best For**   | Workloads with stable hot keys (e.g., product catalog, config)       |
| **Worst For**  | Workloads where popularity shifts rapidly                            |
| **Mitigation** | Use frequency decay (halve frequencies periodically) or window-based |

### 10.3 TTL-Based Expiration

**Concept:** Each key has an explicit time-to-live. Once expired, the key is logically deleted.

**Two-Pronged Cleanup Strategy:**

```
+-----------------------------------------------------------------+
|                TTL Expiration Strategy                           |
+-----------------------------------------------------------------+
|                                                                 |
|  Approach 1: LAZY EXPIRATION (on access)                        |
|  +---------------------------------------------------------+   |
|  | On every GET(key):                                       |   |
|  |   (1) Check if entry.created_at + entry.ttl < now()     |   |
|  |   (2) If expired: delete entry, return MISS              |   |
|  |   (3) If not expired: return value                       |   |
|  |                                                          |   |
|  | Pros: Zero CPU overhead for unexpired keys               |   |
|  | Cons: Expired keys linger in memory if never accessed     |   |
|  +---------------------------------------------------------+   |
|                                                                 |
|  Approach 2: ACTIVE CLEANUP (background thread)                 |
|  +---------------------------------------------------------+   |
|  | Background thread runs every 100ms:                      |   |
|  |   (1) Randomly sample 20 keys from the store            |   |
|  |   (2) Delete any that are expired                        |   |
|  |   (3) If > 25% of sample was expired, repeat immediately|   |
|  |   (4) Otherwise, sleep until next cycle                  |   |
|  |                                                          |   |
|  | Pros: Reclaims memory from never-accessed expired keys   |   |
|  | Cons: Small CPU overhead from background scanning        |   |
|  +---------------------------------------------------------+   |
|                                                                 |
|  COMBINED: Use BOTH approaches together (this is what Redis    |
|  does). Lazy catches expired keys on access; active reclaims    |
|  memory from forgotten keys.                                    |
+-----------------------------------------------------------------+
```

**Active Cleanup Flow:**

```
(1) Background thread wakes up (every 100ms)
(2) Randomly sample 20 keys from the keyspace
(3) Check each key's TTL
(4) Delete expired keys, count them
(5) If expired_count / 20 > 0.25 (more than 25% expired):
      goto (2) immediately -- there are likely more expired keys
(6) Otherwise, sleep for 100ms and repeat

This adaptive approach ensures:
  - Low CPU usage when few keys are expired (most cycles check 20 keys)
  - Aggressive cleanup when many keys expire (e.g., after a bulk TTL event)
```

### 10.4 Eviction Strategy Comparison

| Strategy | Time Complexity | Space Overhead | Scan Resistant | Frequency Aware | TTL Aware |
|----------|----------------|----------------|----------------|-----------------|-----------|
| **LRU**  | O(1) get/put   | 2 pointers/entry | No            | No              | No        |
| **LFU**  | O(1) get/put   | freq counter + bucket pointers | Yes | Yes         | No        |
| **TTL**  | O(1) lazy check| TTL field/entry  | N/A           | N/A             | Yes       |
| **Random**| O(1)          | None             | Yes           | No              | No        |
| **LRU+TTL** | O(1)       | 2 pointers + TTL | No           | No              | Yes       |

> **Interview Recommendation:** Say "I would use **LRU with TTL** as the default policy. LRU handles memory pressure; TTL handles data staleness. This is what Redis uses by default (`volatile-lru` or `allkeys-lru`). LFU is better for specific workloads with stable hot keys, but LRU is the safer general-purpose choice."

### 10.5 Memory Management Policies (maxmemory-policy)

When the cache node hits its `maxmemory` limit, the configured policy determines behavior:

| Policy              | Behavior                                              | Use Case                          |
|---------------------|-------------------------------------------------------|-----------------------------------|
| `noeviction`        | Return error on writes when full                      | Hard upper bound, no data loss    |
| `allkeys-lru`       | Evict any key using LRU                               | General-purpose cache             |
| `allkeys-lfu`       | Evict any key using LFU                               | Stable hot-key workloads          |
| `volatile-lru`      | Evict only keys with TTL set, using LRU               | Mix of persistent + ephemeral data|
| `volatile-lfu`      | Evict only keys with TTL set, using LFU               | Mix with stable access patterns   |
| `allkeys-random`    | Evict a random key                                    | When all keys are equally likely  |
| `volatile-ttl`      | Evict keys with the shortest remaining TTL            | Time-sensitive data               |

---

## 11. Consistent Hashing

### 11.1 The Problem with Simple Hashing

**Naive approach: `node = hash(key) % N`**

```
With 5 nodes:
  hash("user:1") % 5 = 3   --> Node 3
  hash("user:2") % 5 = 0   --> Node 0
  hash("user:3") % 5 = 4   --> Node 4

Problem: Add a 6th node (N changes from 5 to 6):
  hash("user:1") % 6 = 1   --> Node 1  (MOVED from Node 3!)
  hash("user:2") % 6 = 2   --> Node 2  (MOVED from Node 0!)
  hash("user:3") % 6 = 4   --> Node 4  (stayed)

Result: ~80% of keys remap when adding 1 node. CATASTROPHIC cache miss storm.
```

### 11.2 How Consistent Hashing Works

**Concept:** Place both nodes and keys on a circular ring (0 to 2^31 - 1). Each key is assigned to the first node encountered when walking clockwise from the key's hash position.

```
                        0
                        |
              Node A  --+-- 0x0800
                       /    \
                      /      \
           0xE000 --+        +-- 0x2000  Node B
            Node E  |        |
                    |  RING  |
            Node D  |        |
           0xC000 --+        +-- 0x4000  (empty)
                      \      /
                       \    /
              Node C  --+-- 0x8000
                        |
                     0x7FFF...

  Key "user:1" hashes to 0x1500 --> walk clockwise --> hits Node B (0x2000)
  Key "user:2" hashes to 0x9000 --> walk clockwise --> hits Node D (0xC000)
  Key "user:3" hashes to 0xD500 --> walk clockwise --> hits Node E (0xE000)
```

**When Node C is removed:**

```
(1) Only keys between Node D (0xC000) and Node C (0x8000) need to move
(2) Those keys now map to Node D (the next node clockwise)
(3) All other keys are UNAFFECTED
(4) Only K/N keys move (where K = total keys, N = total nodes)
    With 200M keys and 5 nodes: ~40M keys move (20%) instead of ~160M (80%)
```

### 11.3 Virtual Nodes (VNodes)

**Problem with basic consistent hashing:** With only 5 points on the ring, distribution is uneven. One node might get 40% of keys while another gets 10%.

**Solution:** Each physical node gets multiple virtual nodes (150+ is recommended) spread evenly around the ring.

```
Physical Node A gets virtual nodes:
  A-vn0  at position 0x0012
  A-vn1  at position 0x0A34
  A-vn2  at position 0x1456
  A-vn3  at position 0x2078
  ...
  A-vn149 at position 0xFA9C

Physical Node B gets virtual nodes:
  B-vn0  at position 0x00C8
  B-vn1  at position 0x0B12
  ...
  B-vn149 at position 0xFB44

Total ring entries: 5 nodes * 150 vnodes = 750 points on the ring
```

**Distribution with virtual nodes:**

```
Without vnodes (5 physical nodes):
  Node A: 12% | Node B: 35% | Node C: 18% | Node D: 28% | Node E: 7%
  Variance: VERY HIGH (7% to 35%)

With 150 vnodes per node (750 points on ring):
  Node A: 19.5% | Node B: 20.8% | Node C: 19.2% | Node D: 20.5% | Node E: 20.0%
  Variance: LOW (< 2% deviation from ideal 20%)
```

**Why 150 vnodes?**

| VNodes per Node | Max Deviation from Ideal | Memory per Node (ring entries) |
|-----------------|--------------------------|-------------------------------|
| 10              | ~15-20%                  | 50 entries                    |
| 50              | ~5-8%                    | 250 entries                   |
| 100             | ~3-5%                    | 500 entries                   |
| **150**         | **~1-2%**                | **750 entries**               |
| 200             | ~1%                      | 1000 entries                  |
| 500             | < 1%                     | 2500 entries                  |

> 150 vnodes is the sweet spot: deviation is within 2%, and memory for the ring is trivial (750 entries * ~32 bytes = ~24 KB).

### 11.4 Ring Lookup Implementation

```java
class ConsistentHashRing {
    private final TreeMap<Integer, String> ring = new TreeMap<>();  // position -> nodeId
    private final int virtualNodesPerNode = 150;

    void addNode(String nodeId) {
        for (int i = 0; i < virtualNodesPerNode; i++) {
            int hash = murmur3(nodeId + "-vn" + i);
            ring.put(hash, nodeId);
        }
    }

    void removeNode(String nodeId) {
        for (int i = 0; i < virtualNodesPerNode; i++) {
            int hash = murmur3(nodeId + "-vn" + i);
            ring.remove(hash);
        }
    }

    String getNodeFor(String key) {
        int hash = murmur3(key);
        // (1) Find the first ring position >= key's hash (clockwise walk)
        Map.Entry<Integer, String> entry = ring.ceilingEntry(hash);
        // (2) If we wrapped around (past the highest position), use the first entry
        if (entry == null) {
            entry = ring.firstEntry();
        }
        // (3) Return the physical node that owns this position
        return entry.getValue();
    }
}
```

**Time Complexity:**

| Operation      | Complexity  | How                                           |
|----------------|-------------|-----------------------------------------------|
| `getNodeFor()` | O(log V)    | `ceilingEntry` on TreeMap (V = total vnodes)  |
| `addNode()`    | O(P log V)  | P insertions into TreeMap (P = vnodes/node)   |
| `removeNode()` | O(P log V)  | P removals from TreeMap                       |

Where V = total virtual nodes across all physical nodes (e.g., 750), and P = vnodes per physical node (e.g., 150).

### 11.5 Rebalancing When Adding a Node

```
BEFORE: 5 nodes, 750 vnodes on ring, 200M keys
  Each node owns ~40M keys (20% each)

ADD Node F (150 new vnodes scattered on the ring):

(1) Insert 150 vnodes for Node F into the ring
(2) For each new vnode position:
    (a) Identify the key range that now belongs to Node F
        (the range between the new vnode and the previous vnode counterclockwise)
    (b) Those keys previously belonged to the NEXT node clockwise
    (c) Transfer those keys from the old owner to Node F

AFTER: 6 nodes, 900 vnodes on ring, 200M keys
  Each node owns ~33M keys (16.67% each)
  Only ~33M keys moved (16.67%) instead of ~166M (83%) with modulo hashing

Transfer Flow:
  (1) Cluster manager announces "Node F is joining"
  (2) Node F's 150 vnodes are inserted into the ring
  (3) For each affected key range:
      (a) Source node streams matching keys to Node F
      (b) Source node continues serving those keys during transfer
      (c) Once transfer confirmed, source node deletes transferred keys
  (4) Ring update is pushed to all cache clients
  (5) Node F begins serving its key ranges
```

**Rebalancing Diagram:**

```
Before adding Node F:

     ...--[A-vn12]----[B-vn45]----[C-vn78]----[A-vn99]--...
           |           |           |           |
           +--- keys --+--- keys --+--- keys --+
           owned by B   owned by C  owned by A


After adding Node F (new vnode inserted between B-vn45 and C-vn78):

     ...--[A-vn12]----[B-vn45]----[F-vn33]----[C-vn78]----[A-vn99]--...
           |           |           |           |           |
           +--- keys --+--- keys --+--- keys --+--- keys --+
           owned by B   owned by F  owned by C  owned by A
                        (stolen from C)

  Only keys in range (B-vn45, F-vn33] moved from C to F.
  All other key assignments unchanged.
```

---

## 12. Cache Patterns

### 12.1 Cache-Aside (Lazy Loading)

The **most common** pattern. The application is responsible for managing cache reads and writes.

```
READ PATH:
+----------+          +----------+          +----------+
|   App    |          |  Cache   |          | Database |
+----+-----+          +----+-----+          +----+-----+
     |                     |                     |
     | (1) GET(key) ------>|                     |
     |                     |                     |
     | (2a) HIT: value <---|                     |
     |   return value      |                     |
     |                     |                     |
     | --- OR ---          |                     |
     |                     |                     |
     | (2b) MISS <---------|                     |
     |                     |                     |
     | (3) SELECT * FROM...|----- ----------- -->|
     |                     |                     |
     | (4) DB result <-----|---------------------|
     |                     |                     |
     | (5) PUT(key, val) ->|                     |
     |                     |                     |
     | (6) return value    |                     |
     +                     +                     +


WRITE PATH:
+----------+          +----------+          +----------+
|   App    |          |  Cache   |          | Database |
+----+-----+          +----+-----+          +----+-----+
     |                     |                     |
     | (1) UPDATE/INSERT --|-------------------->|
     |                     |                     |
     | (2) DB confirms <---|---------------------|
     |                     |                     |
     | (3) DELETE(key) --->|                     |
     |     (invalidate)    |                     |
     |                     |                     |
     | (4) return success  |                     |
     +                     +                     +
```

**Why DELETE on write, not UPDATE?**

```
Scenario: Two concurrent writes (Thread A and Thread B)

If we UPDATE the cache:
  (1) Thread A writes value=10 to DB
  (2) Thread B writes value=20 to DB     (B overwrites A in DB)
  (3) Thread B updates cache with 20
  (4) Thread A updates cache with 10      (A overwrites B in cache!)
  Result: DB has 20, Cache has 10 --> INCONSISTENT

If we DELETE the cache:
  (1) Thread A writes value=10 to DB
  (2) Thread B writes value=20 to DB
  (3) Thread A deletes cache key
  (4) Thread B deletes cache key
  (5) Next read: cache miss, fetches 20 from DB --> cache has 20
  Result: DB has 20, Cache has 20 --> CONSISTENT
```

| Aspect            | Assessment                                                       |
|-------------------|------------------------------------------------------------------|
| **Pros**          | Simple, resilient to cache failures, only caches what is needed  |
| **Cons**          | First request always misses, potential thundering herd            |
| **Best For**      | Read-heavy workloads, most general-purpose caching               |
| **Used By**       | Most web applications, Memcached-based systems                   |

### 12.2 Read-Through

The cache itself is responsible for loading data on a miss. The application only talks to the cache.

```
+----------+          +----------+          +----------+
|   App    |          |  Cache   |          | Database |
+----+-----+          +----+-----+          +----+-----+
     |                     |                     |
     | (1) GET(key) ------>|                     |
     |                     | (2) Check local     |
     |                     |     store           |
     |                     |                     |
     | (3a) HIT: value <---|                     |
     |                     |                     |
     | --- OR ---          |                     |
     |                     |                     |
     |                     | (3b) MISS:          |
     |                     |   fetch from DB --->|
     |                     |                     |
     |                     | (4) DB result <-----|
     |                     |                     |
     |                     | (5) Store in cache  |
     |                     |                     |
     | (6) value <---------|                     |
     +                     +                     +
```

| Aspect            | Assessment                                                       |
|-------------------|------------------------------------------------------------------|
| **Pros**          | Simpler app code (just call cache), cache handles DB interaction |
| **Cons**          | Cache must know about DB schema, tight coupling                  |
| **Best For**      | When cache layer is a managed service with DB integration        |
| **Used By**       | AWS DAX (DynamoDB Accelerator), Hibernate L2 cache               |

### 12.3 Write-Through

Every write goes through the cache to the database. The cache is always up-to-date.

```
+----------+          +----------+          +----------+
|   App    |          |  Cache   |          | Database |
+----+-----+          +----+-----+          +----+-----+
     |                     |                     |
     | (1) PUT(key, val) ->|                     |
     |                     | (2) Store in cache  |
     |                     |                     |
     |                     | (3) Write to DB --->|
     |                     |                     |
     |                     | (4) DB confirms <---|
     |                     |                     |
     | (5) OK <------------|                     |
     +                     +                     +
```

| Aspect            | Assessment                                                       |
|-------------------|------------------------------------------------------------------|
| **Pros**          | Cache is always consistent with DB, no stale reads               |
| **Cons**          | Write latency increases (cache + DB on every write)              |
| **Best For**      | Data that is read frequently after being written                 |
| **Used By**       | Financial systems, config management                             |

### 12.4 Write-Behind (Write-Back)

Writes go to the cache immediately and are asynchronously flushed to the database in batches.

```
+----------+          +----------+          +----------+
|   App    |          |  Cache   |          | Database |
+----+-----+          +----+-----+          +----+-----+
     |                     |                     |
     | (1) PUT(key, val) ->|                     |
     |                     | (2) Store in cache  |
     |                     |                     |
     | (3) OK (immediate!) |                     |
     |                     |                     |
     |   ... time passes ...|                    |
     |                     |                     |
     |                     | (4) Batch flush     |
     |                     |  (async) ---------->|
     |                     |                     |
     |                     | (5) DB confirms <---|
     +                     +                     +
```

| Aspect            | Assessment                                                       |
|-------------------|------------------------------------------------------------------|
| **Pros**          | Very fast writes (just memory), batching reduces DB load         |
| **Cons**          | Risk of data loss if cache crashes before flush, complexity      |
| **Best For**      | Write-heavy workloads where slight data loss is acceptable       |
| **Used By**       | Leaderboards, counters, session stores, activity feeds           |

### 12.5 Cache Pattern Comparison

| Pattern          | Read Latency | Write Latency | Consistency    | Complexity | Data Loss Risk |
|------------------|-------------|---------------|----------------|------------|----------------|
| **Cache-Aside**  | Miss: high, Hit: low | N/A (writes go to DB) | Eventual | Low        | None           |
| **Read-Through** | Miss: high, Hit: low | N/A                   | Eventual | Medium     | None           |
| **Write-Through**| Hit: low    | High (cache + DB)      | Strong         | Medium     | None           |
| **Write-Behind** | Hit: low    | Very low (cache only)  | Eventual       | High       | **Yes**        |

> **Interview Recommendation:** "For most systems, I would use **cache-aside** because it is simple, the application controls caching logic, and it is resilient to cache failures. I would combine it with TTL for staleness control. If write performance is critical (e.g., counters, leaderboards), I would consider **write-behind** with an acceptable data loss window."

---

## 13. Concurrency

### 13.1 The Problem

A distributed cache node handles thousands of concurrent requests. Without proper concurrency control:

```
Thread A: GET("user:1")              Thread B: PUT("user:1", newValue)
  (1) Read HashMap bucket 42          (1) Compute hash -> bucket 42
  (2) Traverse linked list             (2) Write new entry to bucket 42
  (3) Return stale or corrupted data   (3) Update LRU list

--> Data corruption, lost updates, broken LRU list pointers
```

### 13.2 Lock Striping

Instead of a single global lock (which kills throughput) or per-key locks (which waste memory), use **lock striping**: divide the keyspace into N stripes, each with its own lock.

```
+---------------------------------------------------------------+
|                    Lock Stripe Array                           |
+---------------------------------------------------------------+
| Stripe 0    | Stripe 1    | Stripe 2    | ... | Stripe N-1    |
| Lock[0]     | Lock[1]     | Lock[2]     |     | Lock[N-1]     |
+------+------+------+------+------+------+     +------+--------+
       |             |             |                     |
       v             v             v                     v
  keys where    keys where    keys where           keys where
  hash%N == 0   hash%N == 1   hash%N == 2          hash%N == N-1
```

**How it works:**

```
(1) Compute stripe index: stripe = hash(key) % NUM_STRIPES
(2) Acquire lock for that stripe: locks[stripe].lock()
(3) Perform the operation (GET/PUT/DELETE) on the HashMap
(4) Release the lock: locks[stripe].unlock()

With 256 stripes and 500K req/sec:
  Average contention per stripe: 500K / 256 = ~1,953 req/sec per stripe
  At sub-microsecond lock hold times, contention is minimal
```

**Java Implementation:**

```java
class StripedCacheStore<K, V> {
    private static final int NUM_STRIPES = 256;
    private final ReentrantReadWriteLock[] locks = new ReentrantReadWriteLock[NUM_STRIPES];
    private final Map<K, CacheEntry<K, V>> store = new HashMap<>();

    StripedCacheStore() {
        for (int i = 0; i < NUM_STRIPES; i++) {
            locks[i] = new ReentrantReadWriteLock();
        }
    }

    V get(K key) {
        int stripe = Math.abs(key.hashCode() % NUM_STRIPES);
        locks[stripe].readLock().lock();          // read lock -- allows concurrent reads
        try {
            CacheEntry<K, V> entry = store.get(key);
            if (entry == null || entry.isExpired()) return null;
            return entry.getValue();
        } finally {
            locks[stripe].readLock().unlock();
        }
    }

    void put(K key, V value, long ttlMs) {
        int stripe = Math.abs(key.hashCode() % NUM_STRIPES);
        locks[stripe].writeLock().lock();         // write lock -- exclusive
        try {
            store.put(key, new CacheEntry<>(key, value, ttlMs));
        } finally {
            locks[stripe].writeLock().unlock();
        }
    }
}
```

**Why ReadWriteLock?**

```
ReadWriteLock behavior:
  - Multiple readers can hold the read lock simultaneously   (GET || GET = OK)
  - Only one writer can hold the write lock                  (PUT is exclusive)
  - Writer blocks new readers; readers block writers          (no dirty reads)

With 10:1 read:write ratio:
  - 90% of operations (reads) run with zero contention against each other
  - Only 10% (writes) require exclusive access to a stripe
  - Effective parallelism is very high
```

### 13.3 ConcurrentHashMap (Alternative)

Java's `ConcurrentHashMap` uses internal lock striping (16 segments by default in older Java; node-level CAS in Java 8+). It is a valid choice for the underlying store:

```java
class ConcurrentCacheStore<K, V> {
    private final ConcurrentHashMap<K, CacheEntry<K, V>> store = new ConcurrentHashMap<>();

    V get(K key) {
        CacheEntry<K, V> entry = store.get(key);         // lock-free read
        if (entry == null || entry.isExpired()) return null;
        entry.updateAccessTime();                          // atomic update
        return entry.getValue();
    }

    void put(K key, V value, long ttlMs) {
        store.put(key, new CacheEntry<>(key, value, ttlMs));  // lock-free write
    }

    V computeIfAbsent(K key, Function<K, V> loader) {
        // Atomic: if key is missing, compute and store it
        // Prevents thundering herd: only one thread loads from DB
        return store.computeIfAbsent(key, k -> {
            V value = loader.apply(k);
            return new CacheEntry<>(k, value, DEFAULT_TTL);
        }).getValue();
    }
}
```

> **Interview Note:** `ConcurrentHashMap.computeIfAbsent` is a powerful tool for preventing cache stampede. It guarantees that only one thread executes the loader function for a given key, while other threads wait for the result. Mention this in interviews.

### 13.4 LRU List Concurrency

The LRU doubly-linked list is a separate shared structure that needs its own concurrency strategy:

**Option A: Single lock on the LRU list (simple but coarse)**

```
Every GET/PUT acquires the LRU lock to reorder the list.
Problem: At 500K req/sec, the LRU lock becomes a bottleneck.
```

**Option B: Approximate LRU with sampling (Redis approach)**

```
(1) Do NOT maintain a strict LRU linked list
(2) On eviction, randomly sample 5 keys
(3) Among the 5 samples, evict the one with the oldest last_access timestamp
(4) No linked list, no lock contention

This is what Redis does. It is ~95% as accurate as true LRU
with zero synchronization overhead.
```

**Option C: Per-stripe LRU lists**

```
Each lock stripe maintains its own LRU list:
  Stripe 0: LRU list for keys in stripe 0
  Stripe 1: LRU list for keys in stripe 1
  ...

On eviction, pick the stripe with the most memory usage,
evict from that stripe's LRU tail.

Advantage: LRU operations are protected by the same stripe lock.
No additional synchronization needed.
```

> **Interview Recommendation:** "I would use the **sampling-based approximate LRU** (Option B, the Redis approach) for production. It eliminates the LRU list concurrency problem entirely while being nearly as effective. For an interview coding question specifically about LRU, I would implement Option A with the HashMap + DoublyLinkedList for O(1) correctness."

### 13.5 Thundering Herd / Cache Stampede

**The Problem:**

```
Scenario: Popular key "trending:feed" expires at t=10:00:00

  t=10:00:00  Key expires
  t=10:00:01  1,000 concurrent requests for "trending:feed"
              ALL get a cache miss simultaneously
              ALL query the database simultaneously
              Database gets 1,000 identical queries at once
              Database overloads, latency spikes, cascading failure

+----+  +----+  +----+  +----+       +----+
|Req1|  |Req2|  |Req3|  |Req4| ...   |Req N|
+--+-+  +--+-+  +--+-+  +--+-+       +--+-+
   |       |       |       |              |
   +---+---+---+---+---+---+------+-------+
       |               |          |
       v               v          v
  +----+----+     MISS! MISS! MISS!
  |  Cache  |     (key just expired)
  +---------+
       |    |    |    |         |
       v    v    v    v         v
  +----+----+----+----+---------+----+
  |         DATABASE                 |
  |  1,000 identical queries!        |
  |  OVERWHELMED!                    |
  +----------------------------------+
```

**Solution 1: Locking (Mutex on Cache Miss)**

```
(1) Thread 1 gets cache miss for "trending:feed"
(2) Thread 1 acquires a distributed lock: LOCK("load:trending:feed")
(3) Thread 1 queries the database, stores result in cache
(4) Thread 1 releases the lock
(5) Threads 2-1000: they also got a cache miss, but when they try to
    acquire the lock, it is held. They WAIT (or retry after short sleep).
(6) When they retry, the key is now in cache --> cache HIT

Result: Only 1 database query instead of 1,000
```

```
+----+  +----+  +----+         +----+
|Req1|  |Req2|  |Req3|  ...    |ReqN|
+--+-+  +--+-+  +--+-+         +--+-+
   |       |       |               |
   v       v       v               v
  MISS    MISS    MISS            MISS
   |       |       |               |
   v       |       |               |
  LOCK     |       |               |
acquired   LOCK    LOCK            LOCK
   |      WAIT    WAIT            WAIT
   v       |       |               |
  query    |       |               |
   DB      |       |               |
   |       |       |               |
   v       |       |               |
  store    |       |               |
  in cache |       |               |
   |       |       |               |
  UNLOCK   |       |               |
           v       v               v
          retry   retry           retry
           |       |               |
           v       v               v
          HIT!    HIT!            HIT!
```

**Solution 2: Probabilistic Early Expiration (XFetch)**

```
Instead of all keys expiring at exactly the same time, each request
has a small random chance of refreshing the cache BEFORE expiration.

Algorithm:
  ttl_remaining = entry.expiry_time - now()
  random_threshold = ttl_remaining * BETA * log(random())
  
  if (random_threshold < 0) {
      // This request "wins" the lottery: refresh the cache early
      refresh_from_database(key)
  }

  BETA = tuning parameter (typically 1.0)
  As TTL approaches 0, the probability of early refresh increases

Effect: By the time the key actually expires, it has likely already
been refreshed by one lucky request. No stampede.
```

**Solution 3: Background Refresh (Proactive)**

```
(1) Key "trending:feed" has TTL = 60 seconds
(2) At t=50s (TTL remaining = 10s), a background thread proactively
    refreshes the key from the database
(3) The key never actually expires for users --> zero cache misses

Implementation:
  - Maintain a sorted set of keys by expiry time
  - Background thread picks keys expiring in the next 10 seconds
  - Refresh them before they expire

Pros: Zero stampede, zero cache misses for hot keys
Cons: Extra DB load for pre-fetching, complexity
Best for: Known hot keys with predictable access patterns
```

### 13.6 Hot Key Problem

**The Problem:**

```
One key (e.g., "celebrity:post:viral") receives 100K req/sec.
That key lives on ONE cache node (determined by consistent hashing).
That single node becomes a bottleneck while other nodes are idle.

Normal distribution:       Hot key scenario:
  Node A: 20% traffic        Node A: 80% traffic  <-- overloaded!
  Node B: 20% traffic        Node B:  5% traffic
  Node C: 20% traffic        Node C:  5% traffic
  Node D: 20% traffic        Node D:  5% traffic
  Node E: 20% traffic        Node E:  5% traffic
```

**Solution 1: Local In-Process Cache (L1 Cache)**

```
+-------------------------------------------+
|            Application Server             |
|                                           |
|  +-------------------------------------+ |
|  |      L1 Cache (in-process)          | |
|  |   ConcurrentHashMap, 1000 entries   | |
|  |   TTL: 5 seconds (very short)       | |
|  +------------------+------------------+ |
|                     |                     |
+---------------------|---------------------+
                      | (miss)
                      v
+-------------------------------------------+
|  L2 Cache (Distributed: Redis/Memcached) |
|  Node A: "celebrity:post:viral" lives here|
+-------------------------------------------+

(1) Request for "celebrity:post:viral" hits App Server
(2) Check L1 (local) cache --> HIT? Return immediately (no network call)
(3) L1 MISS: Check L2 (distributed) cache --> return + populate L1
(4) With 50 app servers, each with L1 cache, Node A's traffic drops:
    100K req/sec * 5% L1 miss rate = 5K req/sec to Node A (manageable)
```

**Solution 2: Key Replication Across Nodes**

```
For identified hot keys, replicate the key to ALL cache nodes:

(1) Detect hot key (monitor per-key request rate)
(2) Replicate "celebrity:post:viral" to ALL 5 nodes
(3) Client appends a random suffix: GET("celebrity:post:viral#" + rand(0,4))
(4) Each suffixed key hashes to a different node
(5) Load is spread evenly across all 5 nodes

Normal:    hash("celebrity:post:viral")   --> always Node A
Replicated: hash("celebrity:post:viral#0") --> Node B
            hash("celebrity:post:viral#1") --> Node D
            hash("celebrity:post:viral#2") --> Node A
            hash("celebrity:post:viral#3") --> Node E
            hash("celebrity:post:viral#4") --> Node C
```

**Solution 3: Rate-Based Hot Key Detection**

```
Detection Algorithm (per cache node):
  (1) Maintain a Count-Min Sketch (probabilistic frequency counter)
  (2) For every GET request, increment the key's frequency in the sketch
  (3) If frequency > HOT_KEY_THRESHOLD (e.g., 1000 req/sec):
      (a) Flag key as "hot"
      (b) Notify cluster manager
      (c) Cluster manager replicates key to other nodes
      (d) Client library updated with hot key routing rules

Count-Min Sketch: O(1) increment, O(1) query, fixed memory (~8 KB)
```

### 13.7 Cache Warming

**The Problem:**

```
Scenario: New cache cluster deployed (or cluster restarts after failure)
  - All keys evicted, cache is empty
  - First wave of traffic hits: 100% cache miss rate
  - All requests go to database simultaneously
  - Database overloaded --> cascading failure

This is effectively a system-wide thundering herd.
```

**Warming Strategies:**

```
Strategy 1: Pre-Load from Database
  (1) Before routing traffic, load the top-N most accessed keys from DB
  (2) Use access logs or analytics to identify hot keys
  (3) Bulk-load into cache nodes
  (4) Only then enable traffic routing

Strategy 2: Shadow Traffic / Gradual Ramp-Up
  (1) Deploy new cache cluster alongside old one
  (2) Route 1% of traffic to new cluster
  (3) Gradually increase: 1% -> 5% -> 25% -> 50% -> 100%
  (4) Cache warms up naturally from real traffic at safe load levels

Strategy 3: Snapshot-Based Restore
  (1) Periodically snapshot cache contents to disk (RDB in Redis)
  (2) On restart, load snapshot into memory
  (3) Cache starts warm (though snapshot may be slightly stale)
  (4) Stale entries will be refreshed naturally on access

Strategy 4: Peer Replication
  (1) New node contacts existing nodes
  (2) Requests a copy of all keys that will belong to the new node
      (based on consistent hashing ranges)
  (3) Bulk transfer of key-value pairs
  (4) New node is warm before receiving live traffic
```

**Warming Flow (Strategy 1):**

```
(1) New cache cluster starts with empty nodes
(2) Warming service queries analytics: "top 10M keys by access frequency"
(3) Warming service loads keys from database in batches of 10,000
(4) For each batch:
    (a) Compute hash for each key
    (b) Route to correct cache node
    (c) Bulk PUT into cache node
(5) After loading completes (typically 5-15 minutes):
    (a) Cache hit rate is ~80% on first request
    (b) Load balancer begins routing live traffic
(6) Remaining 20% warms up from real traffic over next few minutes
```

---

## 14. Scaling

### 14.1 Horizontal Scaling: Adding Nodes

```
BEFORE: 5 nodes, each holding ~40M keys, ~64GB used

Traffic growth requires more capacity.

Step-by-step process to add Node F:

(1) Provision new node (Node F) with empty cache
(2) Add Node F to cluster configuration
(3) Insert 150 vnodes for Node F into the consistent hash ring
(4) Identify key ranges that now belong to Node F
    (these were previously owned by other nodes)
(5) Stream affected keys from current owners to Node F
(6) During streaming, current owners still serve those keys (dual-write period)
(7) Once streaming complete:
    (a) Update ring in all cache clients
    (b) New requests for Node F's ranges go to Node F
    (c) Current owners delete transferred keys (async cleanup)

AFTER: 6 nodes, each holding ~33M keys, ~53GB used

Keys moved: ~40M (one node's worth = K/N_new)
Percentage of total keys moved: ~16.7% (only 1/6th, not 83%)
```

```
BEFORE (5 nodes):                    AFTER (6 nodes):

  +------+------+------+              +------+------+------+
  |Node A|Node B|Node C|              |Node A|Node B|Node C|
  | 40M  | 40M  | 40M  |              | 33M  | 33M  | 33M  |
  +------+------+------+              +------+------+------+
  |Node D|Node E|              -->    |Node D|Node E|Node F|
  | 40M  | 40M  |                     | 33M  | 33M  | 33M  |
  +------+------+                     +------+------+------+

  Total: 200M keys                    Total: 200M keys
  Keys moved: ~33M (~16.7%)           (new node got ~7M from each existing node)
```

### 14.2 Horizontal Scaling: Removing Nodes

```
Node D is being decommissioned:

(1) Mark Node D as DRAINING (still serves reads, rejects new writes)
(2) For each of Node D's 150 vnodes:
    (a) Find the next node clockwise on the ring
    (b) That node will inherit Node D's keys for this range
(3) Stream all of Node D's keys to their new owners
(4) Once streaming complete:
    (a) Remove Node D's vnodes from the ring
    (b) Update all cache clients
    (c) Shut down Node D

Keys moved: ~40M (all of Node D's keys)
Distributed across the remaining 4 nodes (~10M each)
```

### 14.3 Vertical Scaling

```
When horizontal scaling is not desired:

Option A: Increase memory per node
  64 GB --> 128 GB per node
  Doubles capacity without adding nodes
  Risk: Longer restart times, bigger blast radius on failure

Option B: Faster CPU / network
  Useful when bottleneck is CPU (serialization) or network (bandwidth)
  Replace nodes with more powerful instances

Limits: Single node cannot exceed ~512 GB practically
  (GC pauses, recovery time, cost)
```

### 14.4 Auto-Scaling Triggers

| Metric                        | Scale OUT Trigger     | Scale IN Trigger     |
|-------------------------------|-----------------------|----------------------|
| Memory usage (per node)       | > 80%                 | < 30% for 30 min    |
| CPU usage (per node)          | > 70%                 | < 20% for 30 min    |
| Cache hit rate                | < 90%                 | > 99% (over-provisioned) |
| Request latency (p99)         | > 5 ms                | N/A                  |
| Eviction rate                 | > 1000/sec            | 0 for 30 min         |
| Connection count              | > 80% of max          | < 20% of max         |

### 14.5 Scaling Summary

```
+-------------------------------------------------------------------+
|                    Scaling Decision Tree                           |
+-------------------------------------------------------------------+
|                                                                   |
|  Q: Is the issue CAPACITY (out of memory)?                        |
|     YES --> Add more nodes (horizontal) or increase RAM (vertical)|
|                                                                   |
|  Q: Is the issue THROUGHPUT (too many req/sec per node)?          |
|     YES --> Add more nodes to spread the load                     |
|     ALSO --> Add read replicas if reads dominate                  |
|                                                                   |
|  Q: Is the issue LATENCY (p99 > target)?                         |
|     YES --> Check if it is network, CPU, or GC                    |
|     Network: add nodes closer to app servers (same AZ)            |
|     CPU: faster instances or fewer keys per node                  |
|     GC: tune JVM (if Java-based), use off-heap memory             |
|                                                                   |
|  Q: Is the issue HOT KEYS?                                        |
|     YES --> L1 local cache + hot key replication                  |
|                                                                   |
+-------------------------------------------------------------------+
```

---

## 15. Database Choice

The cache itself is the primary system, but a backing database is the **source of truth** from which cache misses are populated. The choice of backing database depends on the application.

### 15.1 When a Backing Store Is Needed

| Scenario                    | Backing Store?  | Reason                                      |
|-----------------------------|-----------------|---------------------------------------------|
| Web page caching            | Yes (origin DB) | Cache misses must be populated from somewhere|
| Session store               | Optional        | Cache-only is acceptable if loss is tolerable|
| API response caching        | Yes (origin API)| Cache miss triggers origin API call          |
| Leaderboard / counters      | Optional        | Write-behind to DB for persistence           |
| Feature flags / config      | Yes (config DB) | Must survive cache restart                   |

### 15.2 Backing Database Comparison

| Criteria              | PostgreSQL              | Redis (as backing store) | DynamoDB                  | Cassandra                |
|-----------------------|-------------------------|--------------------------|---------------------------|--------------------------|
| **Access Pattern**    | Complex queries, joins  | Simple key-value          | Simple key-value          | Wide-column, key-value   |
| **Read Latency**      | 2-10 ms (with index)    | < 1 ms                   | 1-5 ms                   | 1-5 ms                  |
| **Write Throughput**  | Moderate                | Very High                 | Very High                 | Very High                |
| **Scaling**           | Vertical + replicas     | Cluster mode              | Auto-scaling              | Horizontal (ring)        |
| **Consistency**       | Strong (ACID)           | Eventual                  | Eventual or Strong        | Tunable                  |
| **Best For**          | Relational data, transactions | Speed-first, ephemeral | Serverless, managed  | Write-heavy, distributed |

### 15.3 The Cache as the Database (Cache-Only Pattern)

In some cases, the cache IS the database:

```
Use Cases:
  - Session store: User sessions live only in cache. If lost, user re-logs in.
  - Rate limiter counters: Counters reset naturally. Loss is acceptable.
  - Real-time leaderboards: Reconstructable from event logs if needed.

Architecture:
  +----------+         +----------+
  |   App    | ------> |  Cache   |   (no backing DB)
  |  Server  | <------ |  Cluster |
  +----------+         +----------+

  Risk: Cache node failure = data loss
  Mitigation: Replication (master + replica) provides durability
```

> **Interview Note:** Always clarify whether the cache needs a backing store. If the interviewer asks "Design a distributed cache," they likely want to see both the cache architecture AND how it interacts with a database. Mention cache-aside as the default pattern.

---

## 16. CAP Theorem

### 16.1 Background

The CAP theorem states that a distributed system can guarantee at most two of:

- **C**onsistency -- Every read returns the most recent write.
- **A**vailability -- Every request receives a response.
- **P**artition Tolerance -- The system works despite network partitions.

Since network partitions are inevitable in distributed systems, the real choice is **CP vs AP**.

### 16.2 Distributed Cache: AP System

| Property             | Stance          | Reasoning                                                        |
|---------------------|-----------------|------------------------------------------------------------------|
| **Availability**    | **Prioritized** | Cache down = all traffic hits DB = system-wide failure. Cache MUST be up. |
| **Consistency**     | Relaxed         | Serving slightly stale data from cache is acceptable. DB is source of truth. |
| **Partition Tolerance** | Required    | Cache nodes across network segments must continue serving.        |

### 16.3 Why AP for a Cache?

```
Scenario: Network partition splits cache cluster

CP Choice (prioritize consistency):
  (1) Partition detected between Node A and Node B
  (2) System refuses to serve reads from Node B until partition heals
  (3) All requests for Node B's keys return errors
  (4) Application falls back to database
  (5) Database overwhelmed --> system-wide outage

AP Choice (prioritize availability):
  (1) Partition detected between Node A and Node B
  (2) Both nodes continue serving reads from their local data
  (3) Node B's data may be slightly stale (missed recent writes)
  (4) When partition heals, nodes reconcile (last-writer-wins)
  (5) System remained available throughout, with minor staleness

For a cache, AP is clearly the right choice because:
  - The database is the source of truth (not the cache)
  - Stale data in cache is a minor inconvenience
  - Cache unavailability causes cascading failures
```

### 16.4 Consistency Tuning

Even in an AP system, you can tune the consistency level:

| Level          | How It Works                            | Latency | Staleness Risk |
|----------------|-----------------------------------------|---------|----------------|
| **Read-local** | Read from any replica (fastest)         | ~0.5 ms | May be stale   |
| **Read-quorum**| Read from majority of replicas          | ~2-3 ms | Minimal        |
| **Write-local**| Write to master only (async replicate)  | ~0.5 ms | Replica may lag |
| **Write-quorum**| Write to majority before acknowledging | ~2-3 ms | Stronger guarantee |

> **Interview Recommendation:** "I would configure the cache as an AP system with async replication. Reads go to the nearest node (master or replica). Writes go to the master and replicate asynchronously. For the rare case where strong consistency is needed, the application can read directly from the database, bypassing the cache."

### 16.5 Conflict Resolution During Partition Healing

```
When a network partition heals, both sides may have conflicting writes:

  Node A (master): PUT("key", "valueA") at t=10:05:00
  Node B (replica, partitioned): PUT("key", "valueB") at t=10:05:02

Resolution Strategies:

  (1) Last-Writer-Wins (LWW): Compare timestamps. "valueB" wins (later).
      Simple, but clock skew can cause incorrect results.
      Mitigation: Use hybrid logical clocks (HLC).

  (2) Master-Wins: Always prefer the master's value.
      Simple, no clock dependency.
      Downside: Writes to replicas during partition are discarded.

  (3) Application-Level Merge: Return both values to the application.
      The application decides how to merge (like CRDTs).
      Complex but correct for all cases.

Recommendation for a cache: Use (1) LWW or (2) Master-Wins.
Caches are ephemeral -- losing a few writes during partition is acceptable.
```

---

## 17. Cloud Services

| Component                | AWS                                   | GCP                            | Azure                          |
|--------------------------|---------------------------------------|--------------------------------|--------------------------------|
| **Managed Cache**        | ElastiCache (Redis / Memcached)       | Memorystore (Redis)            | Azure Cache for Redis          |
| **Serverless Cache**     | ElastiCache Serverless                | N/A                            | Azure Cache (Enterprise tier)  |
| **In-Memory DB**         | MemoryDB for Redis                    | N/A                            | N/A                            |
| **CDN (Edge Cache)**     | CloudFront                            | Cloud CDN                      | Azure CDN / Front Door         |
| **App Servers**          | ECS Fargate / EKS                     | Cloud Run / GKE                | AKS / App Service              |
| **Load Balancer**        | ALB / NLB                             | Cloud Load Balancing           | Azure Load Balancer / App GW   |
| **Monitoring**           | CloudWatch + X-Ray                    | Cloud Monitoring + Trace       | Azure Monitor + App Insights   |
| **Backing DB (NoSQL)**   | DynamoDB                              | Bigtable / Firestore           | Cosmos DB                      |
| **Backing DB (SQL)**     | Aurora PostgreSQL                     | Cloud SQL                      | Azure SQL                      |
| **Config / Discovery**   | Systems Manager Parameter Store       | Secret Manager                 | App Configuration              |
| **Networking**           | VPC, PrivateLink                      | VPC, Private Service Connect   | VNet, Private Link             |

### Redis vs Memcached Decision Matrix

| Feature               | Redis                              | Memcached                       |
|-----------------------|------------------------------------|---------------------------------|
| **Data Structures**   | Strings, hashes, lists, sets, sorted sets | Strings only           |
| **Persistence**       | RDB snapshots, AOF log             | None                            |
| **Replication**       | Master-replica, auto-failover      | None (client-side sharding)     |
| **Clustering**        | Redis Cluster (server-side sharding) | Client-side consistent hashing|
| **Max Memory**        | ~64 GB recommended per node        | ~64 GB per node                 |
| **Multi-threaded**    | Single-threaded event loop (6.0+ has I/O threads) | Multi-threaded  |
| **Pub/Sub**           | Yes                                | No                              |
| **Lua Scripting**     | Yes                                | No                              |
| **Use Case**          | Feature-rich, replication needed   | Simple, multi-threaded, high throughput |
| **When to Choose**    | Need persistence, pub/sub, complex types | Pure key-value, max throughput |

> **Interview Note:** "For a distributed cache system, I would choose **Redis** because it provides built-in replication, clustering, persistence options, and rich data structures. Memcached is faster for pure key-value workloads due to multi-threading, but Redis's operational features (auto-failover, replication) make it the better default choice."

### AWS ElastiCache Architecture Example

```
+--------------------------------------------------+
|                     VPC                           |
|                                                  |
|  +----------+    +----------------------------+  |
|  |   App    |--->|  ElastiCache Redis Cluster  |  |
|  | Servers  |    |                             |  |
|  | (ECS)    |    |  Shard 1: [M] --> [R]       |  |
|  +----------+    |  Shard 2: [M] --> [R]       |  |
|       |          |  Shard 3: [M] --> [R]       |  |
|       |          +----------------------------+  |
|       |                                          |
|       |          +----------------------------+  |
|       +--------->|  DynamoDB (backing store)   |  |
|                  +----------------------------+  |
|                                                  |
+--------------------------------------------------+

(1) App Server sends GET to ElastiCache endpoint
(2) Cluster routes to correct shard based on key hash (CRC16 % 16384 slots)
(3) Master serves read (or replica if read-from-replica enabled)
(4) On cache miss, App Server queries DynamoDB
(5) App Server writes result back to ElastiCache
(6) If Master fails, ElastiCache promotes Replica automatically (~15-30s failover)
```

---

## 18. Tradeoffs Summary

| Decision                        | Option Chosen                 | Alternative                   | Why This Choice                                              |
|---------------------------------|-------------------------------|-------------------------------|--------------------------------------------------------------|
| Partitioning strategy           | Consistent hashing + vnodes   | Modulo hashing                | Minimal key movement on add/remove node                      |
| Virtual nodes per node          | 150                           | 50 / 500                     | Sweet spot: < 2% deviation, minimal memory                   |
| Eviction policy                 | LRU with TTL (default)        | LFU, Random                  | General-purpose, simple, effective for most workloads         |
| Replication model               | Async master-replica          | Sync replication              | Cache prioritizes latency over durability (DB is truth)      |
| CAP tradeoff                    | AP (availability + partition) | CP                            | Cache unavailability causes cascading DB failures            |
| Cache pattern                   | Cache-aside                   | Read-through, write-through   | Simple, application-controlled, resilient to cache failures  |
| Concurrency strategy            | Lock striping (256 stripes)   | Global lock, ConcurrentHashMap| Balance between contention and memory overhead               |
| LRU implementation              | Sampling-based approximate    | Strict doubly-linked list     | No lock contention, ~95% accuracy, production-proven (Redis) |
| Stampede prevention             | Mutex on cache miss           | Probabilistic early expiration| Simple, guaranteed single-load, widely understood            |
| Hot key mitigation              | L1 local cache + replication  | Nothing (accept hot spots)    | 95%+ reduction in hot-node traffic                           |
| Serialization format            | Protobuf                      | JSON, Kryo                    | Compact binary, schema evolution, language-agnostic          |
| Hash function                   | MurmurHash3                   | MD5, CRC32, XXH3             | Fast, good distribution, widely used in caches               |
| Conflict resolution             | Last-Writer-Wins (LWW)        | Master-wins, CRDTs           | Simple, acceptable for ephemeral cache data                  |
| Cache warming                   | Pre-load top-N keys           | Cold start                   | Prevents thundering herd on deployment                       |
| Backing database                | DynamoDB / Cassandra          | PostgreSQL                    | Key-value access pattern, horizontal scaling                 |

---

## 19. Interview Talking Points

Use these as a mental checklist during the interview. Proactively mention these to demonstrate depth.

### Opening (2 min)

- Clarify requirements: "Is this a general-purpose cache like Redis/Memcached, or a specific caching layer for an application?"
- State assumptions explicitly (cluster size, traffic, read:write ratio, latency targets).
- Define scope: in-scope and out-of-scope features.
- Mention that the database remains the source of truth.

### Estimation (3 min)

- Walk through back-of-the-envelope math: keys, memory per entry, total cluster memory needed.
- Show reads/sec, writes/sec, memory footprint.
- Mention the 80/20 rule (Pareto): 20% of keys serve 80% of traffic.

### Core Design (10 min)

- Draw the architecture: App Servers -> Cache Client -> Consistent Hash Ring -> Cache Nodes (Master + Replica) -> Database (source of truth).
- Explain each component briefly.
- Focus on the **read path** (cache hit vs miss) and **write path** (cache-aside pattern).

### Deep Dives to Proactively Offer

- **"Let me explain how consistent hashing distributes keys..."** -- Draw the ring, show virtual nodes, explain why 150 vnodes. Show what happens when a node is added (only K/N keys move). This demonstrates distributed systems knowledge.

- **"For the LRU eviction, I would use HashMap + DoublyLinkedList for O(1)..."** -- Walk through the data structure. Mention that Redis actually uses approximate LRU with sampling for production. This shows you know theory AND practice.

- **"Let me address the thundering herd problem..."** -- Explain cache stampede, show the mutex solution, mention probabilistic early expiration. This shows production awareness.

- **"For hot keys, I would add an L1 local cache..."** -- Explain the two-tier caching strategy. Mention key replication for extreme cases. This shows you think about edge cases.

- **"For concurrency, I would use lock striping with ReadWriteLock..."** -- Explain why not a global lock, why not per-key locks. Show the stripe math. This demonstrates Java concurrency expertise.

- **"The CAP tradeoff here is AP..."** -- Explain why availability matters more than consistency for a cache. Mention that the database is the source of truth.

### Red Flags to Avoid

- Do NOT forget consistent hashing. Modulo hashing is a deal-breaker answer.
- Do NOT ignore replication. A cache without replicas is a single point of failure.
- Do NOT skip eviction policies. The interviewer expects LRU at minimum, LFU as a bonus.
- Do NOT treat the cache as the source of truth. Always mention the backing database.
- Do NOT hand-wave concurrency. Show you understand thread safety for a multi-threaded cache server.
- Do NOT forget about cache invalidation ("the two hard problems in CS").

### Bonus Points

- Mention **cache warming** strategies for cold starts and deployments.
- Mention **monitoring metrics**: hit rate, eviction rate, memory usage, p99 latency, replication lag.
- Mention **circuit breaker** pattern: if cache is down, rate-limit DB queries to prevent cascade.
- Mention **compression**: compress values > 1 KB to save memory and network bandwidth.
- Mention **connection pooling**: reuse TCP connections to cache nodes (critical for latency).
- Mention **near-cache / L1 cache**: in-process cache for ultra-hot keys (5-second TTL).
- Mention **cache-aside with DELETE, not UPDATE**: explain the race condition that makes DELETE safer.
- Mention that `ConcurrentHashMap.computeIfAbsent` in Java is a built-in stampede prevention tool.

### The 30-Second Elevator Pitch

> "A distributed cache is an in-memory key-value store spread across multiple nodes using consistent hashing with virtual nodes for even distribution. Data is partitioned by key hash and replicated to a replica node for fault tolerance. Eviction is handled by LRU (HashMap + DoublyLinkedList for O(1)), and TTL provides staleness control. The cache uses a cache-aside pattern where the application checks the cache first, falls back to the database on miss, and writes back to cache. Key challenges include thundering herd (solved by mutex on miss), hot keys (solved by L1 local cache), and rebalancing on node changes (solved by consistent hashing minimizing key movement). The system prioritizes availability over consistency (AP) since the database is the source of truth."

---

> **Final Tip:** The interviewer is not looking for a perfect production design.
> They want to see structured thinking, tradeoff analysis, and the ability to
> communicate clearly under time pressure. Lead with the big picture, then
> offer to dive deep into any component they find interesting. For a distributed
> cache specifically, the three areas that impress most are: (1) consistent hashing
> with virtual nodes, (2) LRU/LFU data structure internals, and (3) thundering
> herd / cache stampede handling.
