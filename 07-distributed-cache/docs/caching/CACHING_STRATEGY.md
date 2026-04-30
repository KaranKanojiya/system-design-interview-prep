# Caching Strategies for the Distributed Cache

> Interview-ready reference for a Senior Java developer.
> This IS the caching project -- so this document goes deep on every caching pattern, failure mode, and optimization.
> Every pattern has a numbered ASCII flow diagram.

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| Cache-Aside (Lazy Loading) | Application manages cache + DB independently |
| Read-Through | Cache sits in front of DB, loads on miss |
| Write-Through | Writes go to cache AND DB synchronously |
| Write-Behind (Write-Back) | Writes go to cache, async flush to DB |
| Refresh-Ahead | Proactively refresh before TTL expires |
| Cache Invalidation | TTL, event-driven, versioned |
| Thundering Herd / Cache Stampede | Problem + 4 solutions |
| Hot Key Problem | Problem + 3 solutions |
| Cache Warming | Pre-populate on deploy |
| Multi-Level Caching | L1 local + L2 distributed |
| Cache Sizing & Memory | How much memory do you need? |
| Comparison Table | When to use each pattern |

---

## 1. Cache-Aside (Lazy Loading)

The most common caching pattern. The application is responsible for reading from and writing to both the cache and the database. The cache does not interact with the database at all.

### Numbered Flow -- Cache Hit

```
  Client               Application            Cache              Database
    |                       |                    |                    |
    | (1) GET /user/1       |                    |                    |
    |---------------------->|                    |                    |
    |                       | (2) cache.get      |                    |
    |                       |   ("user:1")       |                    |
    |                       |------------------>|                    |
    |                       |                    |                    |
    |                       | (3) CACHE HIT      |                    |
    |                       |   return userData  |                    |
    |                       |<------------------|                    |
    |                       |                    |                    |
    | (4) 200 OK            |                    |                    |
    |   {user data}         |                    |                    |
    |<----------------------|                    |                    |
    |                       |                    |                    |
    |   Database NOT touched. Latency: ~1ms      |                    |
```

### Numbered Flow -- Cache Miss

```
  Client               Application            Cache              Database
    |                       |                    |                    |
    | (1) GET /user/1       |                    |                    |
    |---------------------->|                    |                    |
    |                       | (2) cache.get      |                    |
    |                       |   ("user:1")       |                    |
    |                       |------------------>|                    |
    |                       |                    |                    |
    |                       | (3) CACHE MISS     |                    |
    |                       |   return null      |                    |
    |                       |<------------------|                    |
    |                       |                    |                    |
    |                       | (4) db.query       |                    |
    |                       |   ("SELECT * FROM  |                    |
    |                       |    users WHERE     |                    |
    |                       |    id = 1")        |                    |
    |                       |------------------------------------------->|
    |                       |                    |                    |
    |                       | (5) return         |                    |
    |                       |   userData         |                    |
    |                       |<------------------------------------------|
    |                       |                    |                    |
    |                       | (6) cache.put      |                    |
    |                       |  ("user:1", data,  |                    |
    |                       |   TTL=300s)        |                    |
    |                       |------------------>|                    |
    |                       |                    |                    |
    | (7) 200 OK            |                    |                    |
    |   {user data}         |                    |                    |
    |<----------------------|                    |                    |
    |                       |                    |                    |
    |   Latency: ~10ms (DB query). Next request will be a cache hit. |
```

### Code

```java
public class UserService {
    private final CacheService cache;
    private final UserRepository db;

    public User getUser(String userId) {
        // (1) Try cache first
        User cached = (User) cache.get("user:" + userId);
        if (cached != null) {
            return cached;                     // (2) Cache hit -- fast path
        }

        // (3) Cache miss -- load from DB
        User user = db.findById(userId)
            .orElseThrow(() -> new NotFoundException("User not found"));

        // (4) Populate cache for next time
        cache.put("user:" + userId, user);

        return user;
    }

    public void updateUser(String userId, User updated) {
        // (1) Update DB first (source of truth)
        db.save(updated);

        // (2) Invalidate cache (don't update -- avoids race conditions)
        cache.remove("user:" + userId);
    }
}
```

### Pros and Cons

| Pros | Cons |
|------|------|
| Simple to implement | Cache miss penalty (cold start) |
| Only caches what's actually requested | Stale data possible (TTL window) |
| Cache failure = degrade to DB (resilient) | Application must manage both cache and DB |
| No unnecessary data cached | N+1 cache miss problem on cold start |

---

## 2. Read-Through

The cache sits between the application and the database. On a cache miss, the cache itself loads the data from the database. The application only talks to the cache.

### Numbered Flow

```
  Client               Application          Cache                Database
    |                       |                  |                      |
    | (1) GET /user/1       |                  |                      |
    |---------------------->|                  |                      |
    |                       | (2) cache.get    |                      |
    |                       |  ("user:1")      |                      |
    |                       |---------------->|                      |
    |                       |                  |                      |
    |                       |                  | (3) MISS -- cache    |
    |                       |                  |   loads from DB      |
    |                       |                  |   itself             |
    |                       |                  |--------------------->|
    |                       |                  |                      |
    |                       |                  | (4) DB returns data  |
    |                       |                  |<--------------------|
    |                       |                  |                      |
    |                       |                  | (5) cache stores     |
    |                       |                  |   data internally    |
    |                       |                  |                      |
    |                       | (6) return data  |                      |
    |                       |<----------------|                      |
    |                       |                  |                      |
    | (7) 200 OK            |                  |                      |
    |<----------------------|                  |                      |
    |                       |                  |                      |
    |   Application code is SIMPLER -- just calls cache.get()        |
    |   Cache handles the DB fallback transparently                  |
```

### Code

```java
// Cache with built-in loader
public class ReadThroughCache {
    private final CacheStore store;
    private final Function<String, Object> loader;  // DB lookup function

    public ReadThroughCache(CacheStore store, Function<String, Object> loader) {
        this.store = store;
        this.loader = loader;
    }

    public Object get(String key) {
        Optional<CacheEntry> cached = store.get(key);
        if (cached.isPresent() && !cached.get().isExpired()) {
            return cached.get().getValue();    // cache hit
        }

        // Cache miss -- load from source
        Object value = loader.apply(key);      // calls DB
        if (value != null) {
            CacheEntry entry = CacheEntry.builder()
                .key(key).value(value).ttlSeconds(300).build();
            store.put(key, entry);
        }
        return value;
    }
}

// Application code -- simple:
ReadThroughCache cache = new ReadThroughCache(store,
    key -> userRepository.findById(key.replace("user:", "")).orElse(null));

User user = (User) cache.get("user:1");  // one call, cache handles miss
```

### Difference from Cache-Aside

| Aspect | Cache-Aside | Read-Through |
|--------|-------------|-------------|
| Who loads from DB on miss? | Application | Cache |
| Application knows about DB? | Yes | No (for reads) |
| Code complexity | Higher (manage cache + DB) | Lower (just call cache) |
| Cache library requirement | Simple get/put | Must support loader function |

---

## 3. Write-Through

Every write goes to BOTH the cache and the database synchronously. The write is not considered complete until both are updated.

### Numbered Flow

```
  Client               Application          Cache                Database
    |                       |                  |                      |
    | (1) PUT /user/1       |                  |                      |
    |  {name: "Alicia"}     |                  |                      |
    |---------------------->|                  |                      |
    |                       | (2) cache.put    |                      |
    |                       |  ("user:1",      |                      |
    |                       |   userData)      |                      |
    |                       |---------------->|                      |
    |                       |                  |                      |
    |                       |                  | (3) write to DB     |
    |                       |                  |   synchronously     |
    |                       |                  |--------------------->|
    |                       |                  |                      |
    |                       |                  | (4) DB confirms     |
    |                       |                  |<--------------------|
    |                       |                  |                      |
    |                       |                  | (5) store in cache  |
    |                       |                  |                      |
    |                       | (6) ACK          |                      |
    |                       |<----------------|                      |
    |                       |                  |                      |
    | (7) 200 OK            |                  |                      |
    |<----------------------|                  |                      |
    |                       |                  |                      |
    |   Latency: cache write + DB write (higher than cache-aside)   |
    |   Guarantee: cache and DB are ALWAYS in sync                   |
```

### Code

```java
public class WriteThroughCache {
    private final CacheStore store;
    private final BiConsumer<String, Object> writer;  // DB write function

    public void put(String key, Object value) {
        // (1) Write to DB first (if DB fails, don't cache)
        writer.accept(key, value);

        // (2) Update cache (DB succeeded, safe to cache)
        CacheEntry entry = CacheEntry.builder()
            .key(key).value(value).ttlSeconds(300).build();
        store.put(key, entry);
    }
}
```

### Pros and Cons

| Pros | Cons |
|------|------|
| Cache is always consistent with DB | Write latency doubled (cache + DB) |
| No stale data | Caches data that may never be read |
| Simple mental model | DB failure requires cache rollback logic |

---

## 4. Write-Behind (Write-Back)

Writes go to the cache immediately. The cache asynchronously flushes writes to the database in the background. This decouples write latency from database latency.

### Numbered Flow

```
  Client            Application          Cache              Write Queue     Database
    |                    |                  |                     |              |
    | (1) PUT /user/1    |                  |                     |              |
    |  {name: "Alicia"}  |                  |                     |              |
    |------------------>|                  |                     |              |
    |                    | (2) cache.put    |                     |              |
    |                    |  ("user:1",      |                     |              |
    |                    |   userData)      |                     |              |
    |                    |---------------->|                     |              |
    |                    |                  |                     |              |
    |                    |                  | (3) store in cache  |              |
    |                    |                  |   immediately       |              |
    |                    |                  |                     |              |
    |                    |                  | (4) enqueue DB      |              |
    |                    |                  |   write             |              |
    |                    |                  |------------------->|              |
    |                    |                  |                     |              |
    |                    | (5) ACK          |                     |              |
    |                    |<----------------|                     |              |
    |                    |                  |                     |              |
    | (6) 200 OK         |                  |                     |              |
    |<------------------|                  |                     |              |
    |                    |                  |                     |              |
    |   CLIENT DONE.     |                  |                     |              |
    |   DB write happens |                  |                     |              |
    |   asynchronously:  |                  |                     |              |
    |                    |                  |                     | (7) batch    |
    |                    |                  |                     |   flush to   |
    |                    |                  |                     |   DB (every  |
    |                    |                  |                     |   100ms or   |
    |                    |                  |                     |   100 items) |
    |                    |                  |                     |------------>|
    |                    |                  |                     |              |
    |                    |                  |                     | (8) DB ACK   |
    |                    |                  |                     |<------------|
```

### Code

```java
public class WriteBehindCache {
    private final CacheStore store;
    private final BlockingQueue<WriteRequest> writeQueue;
    private final ScheduledExecutorService flusher;

    public WriteBehindCache(CacheStore store, Consumer<List<WriteRequest>> batchWriter) {
        this.store = store;
        this.writeQueue = new LinkedBlockingQueue<>();

        // Background flusher -- batches writes every 100ms
        this.flusher = Executors.newSingleThreadScheduledExecutor();
        flusher.scheduleAtFixedRate(() -> {
            List<WriteRequest> batch = new ArrayList<>();
            writeQueue.drainTo(batch, 100);  // up to 100 items
            if (!batch.isEmpty()) {
                batchWriter.accept(batch);   // batch write to DB
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    public void put(String key, Object value) {
        // (1) Update cache immediately
        CacheEntry entry = CacheEntry.builder()
            .key(key).value(value).ttlSeconds(300).build();
        store.put(key, entry);

        // (2) Enqueue DB write (async)
        writeQueue.offer(new WriteRequest(key, value));
    }
}
```

### Pros and Cons

| Pros | Cons |
|------|------|
| Fastest write latency (cache only) | Data loss risk (cache crash before flush) |
| Batch writes reduce DB load | Complex failure handling |
| Write coalescing (multiple writes to same key = one DB write) | Eventually consistent |

---

## 5. Refresh-Ahead

Proactively refresh cache entries BEFORE they expire. If a key's TTL is about to expire and it's frequently accessed, refresh it in the background so the next read is always a cache hit.

### Numbered Flow

```
  Background             Cache              Database            Client
  Refresher                |                    |                  |
    |                      |                    |                  |
    | (1) scan for keys    |                    |                  |
    |   expiring within    |                    |                  |
    |   30 seconds         |                    |                  |
    |--------------------->|                    |                  |
    |                      |                    |                  |
    | (2) found "user:1"   |                    |                  |
    |   TTL = 25s remaining|                    |                  |
    |   access count = 150 |                    |                  |
    |   (hot key)          |                    |                  |
    |                      |                    |                  |
    | (3) fetch fresh      |                    |                  |
    |   data from DB       |                    |                  |
    |--------------------------------------------->|                  |
    |                      |                    |                  |
    | (4) DB returns       |                    |                  |
    |   fresh data         |                    |                  |
    |<---------------------------------------------|                  |
    |                      |                    |                  |
    | (5) cache.put        |                    |                  |
    |  ("user:1",          |                    |                  |
    |   freshData,         |                    |                  |
    |   TTL=300s)          |                    |                  |
    |--------------------->|                    |                  |
    |                      |                    |                  |
    |   Entry refreshed before expiry.          |                  |
    |   Client never sees a cache miss:         |                  |
    |                      |                    |                  |
    |                      |                    |  (6) GET /user/1 |
    |                      |                    |  <----------------|
    |                      |  CACHE HIT (always)|                  |
    |                      |------------------------------------- >|
```

### Code

```java
public class RefreshAheadCache {
    private final CacheStore store;
    private final Function<String, Object> loader;
    private final ScheduledExecutorService refresher;
    private final double refreshThreshold;  // e.g., 0.1 = refresh when 10% TTL remaining

    public RefreshAheadCache(CacheStore store, Function<String, Object> loader) {
        this.store = store;
        this.loader = loader;
        this.refreshThreshold = 0.1;  // refresh when <10% TTL left

        this.refresher = Executors.newScheduledThreadPool(2);
        refresher.scheduleAtFixedRate(this::refreshExpiring, 10, 10, TimeUnit.SECONDS);
    }

    private void refreshExpiring() {
        for (String key : store.keys()) {
            store.get(key).ifPresent(entry -> {
                if (shouldRefresh(entry)) {
                    // Async refresh -- don't block
                    refresher.submit(() -> {
                        Object freshValue = loader.apply(key);
                        if (freshValue != null) {
                            CacheEntry newEntry = CacheEntry.builder()
                                .key(key).value(freshValue).ttlSeconds(300).build();
                            store.put(key, newEntry);
                        }
                    });
                }
            });
        }
    }

    private boolean shouldRefresh(CacheEntry entry) {
        if (entry.getExpiresAt() == null) return false;
        long ttlRemaining = Duration.between(LocalDateTime.now(), entry.getExpiresAt()).toSeconds();
        long totalTtl = Duration.between(entry.getCreatedAt(), entry.getExpiresAt()).toSeconds();
        return totalTtl > 0 && ((double) ttlRemaining / totalTtl) < refreshThreshold;
    }
}
```

### Pros and Cons

| Pros | Cons |
|------|------|
| Eliminates cache miss latency for hot keys | Background threads consume resources |
| Seamless user experience | Refreshes data that might not be read again |
| Reduces thundering herd risk | Complex to tune refresh threshold |

---

## 6. Cache Invalidation Strategies

### TTL (Time-To-Live)

```
  PUT "user:1" with TTL=300s
  |
  |  T=0        T=150       T=299       T=300       T=301
  |  [VALID]    [VALID]     [VALID]     [EXPIRED]   [EXPIRED]
  |                                         |
  |                                    Next GET:
  |                                    (1) Check isExpired() -> true
  |                                    (2) Remove from cache
  |                                    (3) Return null (miss)
  |                                    (4) Application fetches from DB
  |                                    (5) Repopulate cache with new TTL

  Lazy expiration: entries aren't removed until accessed (or background sweep)
```

### Event-Driven Invalidation

```
  User Service              Message Bus            Cache Service
       |                        |                       |
       | (1) UPDATE user:1      |                       |
       |   in database          |                       |
       |                        |                       |
       | (2) PUBLISH            |                       |
       |   "user.updated"       |                       |
       |   {userId: 1}         |                       |
       |----------------------->|                       |
       |                        |                       |
       |                        | (3) DELIVER event    |
       |                        |--------------------->|
       |                        |                       |
       |                        |                       | (4) cache.remove
       |                        |                       |   ("user:1")
       |                        |                       |
       |   Advantage: No TTL delay -- cache invalidated immediately
       |   Disadvantage: Requires message bus infrastructure (Kafka, Redis Pub/Sub)
```

### Versioned Invalidation

```
  DB version: 5                    Cache stores: "user:1:v5" -> data

  DB updated, version -> 6        Cache key "user:1:v5" still exists
                                    but application requests "user:1:v6"
                                    -> MISS (different version)
                                    -> Fetch from DB
                                    -> Store as "user:1:v6"

  Old entry "user:1:v5" expires via TTL (no explicit delete needed)
```

### Comparison

| Strategy | Staleness | Complexity | Use Case |
|----------|-----------|------------|----------|
| TTL | Bounded (0 to TTL) | Low | Default for most data |
| Event-Driven | Near zero | High (needs message bus) | User sessions, inventory |
| Versioned | Zero (always fresh) | Medium | API responses, config |
| Manual (delete) | Zero | Low | Admin operations |

---

## 7. Thundering Herd / Cache Stampede

### The Problem

```
  T=0: Popular key "trending:posts" expires (TTL reached)
  
  Thread 1 -----> cache.get("trending:posts") -> MISS -> query DB
  Thread 2 -----> cache.get("trending:posts") -> MISS -> query DB
  Thread 3 -----> cache.get("trending:posts") -> MISS -> query DB
  ...
  Thread 1000 --> cache.get("trending:posts") -> MISS -> query DB
  
  Result: 1000 IDENTICAL database queries at the same instant
  Database: "I can't handle this!" -> timeout -> cascading failure
  
  +----------+                  +-----------+
  | 1000     |   1000 identical | Database  |
  | cache    | ===============> | (overload)|
  | misses   |   queries        |   X X X   |
  +----------+                  +-----------+
```

### Solution 1: Mutex / Lock (Request Coalescing)

```
  Thread 1 -----> cache.get() -> MISS -> acquireLock("trending:posts") -> GOT LOCK
                                          -> query DB
                                          -> cache.put(result)
                                          -> releaseLock()
  
  Thread 2 -----> cache.get() -> MISS -> acquireLock() -> BLOCKED (lock held)
                                          -> wait...
                                          -> lock released
                                          -> cache.get() -> HIT!
  
  Thread 3 -----> cache.get() -> MISS -> acquireLock() -> BLOCKED
                                          -> wait...
                                          -> cache.get() -> HIT!
  
  Result: ONLY ONE database query. 999 threads wait, then read from cache.
```

```java
public class StampedeProtectedCache {
    private final CacheService cache;
    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    public Object getOrLoad(String key, Function<String, Object> loader) {
        // (1) Try cache
        Object cached = cache.get(key);
        if (cached != null) return cached;

        // (2) Cache miss -- acquire per-key lock
        ReentrantLock lock = keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            // (3) Double-check after acquiring lock
            cached = cache.get(key);
            if (cached != null) return cached;  // another thread populated it

            // (4) Only ONE thread reaches here
            Object value = loader.apply(key);
            cache.put(key, value);
            return value;
        } finally {
            lock.unlock();
            keyLocks.remove(key);  // clean up lock
        }
    }
}
```

### Solution 2: Stale-While-Revalidate

```
  Key "trending:posts"
  TTL = 300s (hard)
  Soft TTL = 270s (serve stale but trigger background refresh)

  T=270: Soft TTL expires
  Thread 1 -> cache.get() -> data found but SOFT EXPIRED
           -> return STALE data immediately (user sees it)
           -> trigger background refresh (async DB query)
  
  Thread 2 -> cache.get() -> data still in cache (hard TTL not expired)
           -> return STALE data (background refresh in progress)
  
  Background thread -> DB query completes -> cache.put(fresh data, new TTL)
  
  Thread 3 -> cache.get() -> FRESH data (background refresh completed)

  Result: No thread ever blocks. Users see stale data for ~1 second.
```

### Solution 3: Probabilistic Early Expiration

```
  Instead of all requests hitting at TTL:
  
  TTL = 300s
  At T=290: 1% chance of refresh (dice roll)
  At T=295: 5% chance of refresh
  At T=299: 50% chance of refresh
  At T=300: 100% (expired)
  
  ONE unlucky thread refreshes at T=295, the rest see cached data.
  By T=300, cache is already refreshed -- no stampede.
  
  Formula: shouldRefresh = random() < exp(-ttlRemaining * beta)
```

### Solution 4: External Cache Lock (Redis SETNX)

```java
// Using Redis as a distributed lock
public Object getOrLoad(String key, Function<String, Object> loader) {
    Object cached = cache.get(key);
    if (cached != null) return cached;

    String lockKey = "lock:" + key;
    // SETNX: Set if Not Exists (atomic)
    boolean gotLock = redis.setnx(lockKey, "1", Duration.ofSeconds(10));

    if (gotLock) {
        try {
            Object value = loader.apply(key);
            cache.put(key, value);
            return value;
        } finally {
            redis.del(lockKey);
        }
    } else {
        // Another instance is loading -- wait briefly and retry
        Thread.sleep(50);
        return getOrLoad(key, loader);  // retry (with exponential backoff)
    }
}
```

---

## 8. Hot Key Problem

### The Problem

```
  Key "celebrity:post:12345" -- 100,000 reads/second
  
  Consistent hashing maps this key to Node B
  
  Node A: 1,000 req/s   (normal)
  Node B: 101,000 req/s  (overloaded -- hot key + normal traffic)
  Node C: 1,000 req/s   (normal)
  
  +--------+     +--------+     +--------+
  | Node A |     | Node B |     | Node C |
  | 1K/s   |     | 101K/s |     | 1K/s   |
  | (idle) |     | (CPU   |     | (idle) |
  |        |     |  100%) |     |        |
  +--------+     +--------+     +--------+
```

### Solution 1: Local Cache (L1 + L2)

```
  Each application server has a LOCAL cache (L1) in front of distributed cache (L2)

  App Server 1       App Server 2       App Server 3
  +----------+       +----------+       +----------+
  | L1 Cache |       | L1 Cache |       | L1 Cache |
  | (Caffeine)|      | (Caffeine)|      | (Caffeine)|
  | TTL=10s  |       | TTL=10s  |       | TTL=10s  |
  +----+-----+       +----+-----+       +----+-----+
       |                  |                  |
       +------ L2 Distributed Cache (Redis) ------+
       |                  |                  |
  +----+-----+       +----+-----+       +----+-----+
  | Node A   |       | Node B   |       | Node C   |
  +----------+       +----------+       +----------+

  Hot key "celebrity:post:12345":
  - First request on each app server -> L1 miss -> L2 hit -> store in L1
  - Next 10 seconds: all reads served from L1 (NO network call to L2)
  - Node B load drops from 101K/s to ~100/s (only L1 misses reach it)
```

### Solution 2: Key Replication (Read Replicas for Hot Keys)

```
  Detect hot key (>10K reads/s) -> replicate to ALL nodes

  Before:
  Node A: -                 Node B: "celebrity:post" -> data    Node C: -

  After hot key detection:
  Node A: "celebrity:post"  Node B: "celebrity:post"            Node C: "celebrity:post"
  -> data (replica)         -> data (primary)                   -> data (replica)

  Client reads "celebrity:post":
  - Random node selected (or round-robin)
  - Load spread across 3 nodes instead of 1
```

### Solution 3: Key Sharding (Suffix Randomization)

```
  Original key: "celebrity:post:12345"
  Sharded keys: "celebrity:post:12345:shard0"  -> Node A
                "celebrity:post:12345:shard1"  -> Node B
                "celebrity:post:12345:shard2"  -> Node C

  Write: write to ALL shards
  Read:  pick random shard -> random("celebrity:post:12345:shard" + rand(3))

  Load distributed across all nodes.
  Tradeoff: 3x write amplification, slightly more complex read logic.
```

```java
public class ShardedHotKeyCache {
    private static final int SHARD_COUNT = 3;
    private final CacheService cache;

    public void putHotKey(String key, Object value) {
        // Write to all shards
        for (int i = 0; i < SHARD_COUNT; i++) {
            cache.put(key + ":shard" + i, value);
        }
    }

    public Object getHotKey(String key) {
        // Read from random shard
        int shard = ThreadLocalRandom.current().nextInt(SHARD_COUNT);
        return cache.get(key + ":shard" + shard);
    }
}
```

---

## 9. Cache Warming Strategies

### The Problem

```
  Deploy new cache cluster:
  
  T=0:   All caches empty
  T=0+:  ALL requests -> cache MISS -> database
  
  +--------+    100% miss    +--------+    100% traffic    +--------+
  | Clients| =============> | Empty  | ==================>| Database|
  |        |                | Cache  |                    | (melts) |
  +--------+                +--------+                    +--------+
```

### Strategy 1: Preload from Database

```
  BEFORE directing traffic to new cache:

  (1) Query DB for top 1000 most-accessed keys
  (2) Bulk-load into cache
  (3) Enable traffic

  +--------+     (1) SELECT     +--------+     (2) BULK PUT    +--------+
  | Warming|  ===============>  |Database|  =================> | Cache  |
  | Script |     top keys       +--------+     preload data    | (warm) |
  +--------+                                                    +--------+
                                                                     |
                                                                (3) READY
                                                                     |
                                                               +--------+
                                                               | Clients|
                                                               | (send  |
                                                               | traffic)|
                                                               +--------+
```

### Strategy 2: Shadow Traffic (Canary Warming)

```
  Old cache cluster (serving traffic)     New cache cluster (warming)
  +--------+                              +--------+
  | Cache  | <--- 100% traffic            | Cache  | <--- 0% traffic (shadow)
  | v1     |                              | v2     |
  +--------+                              +--------+
       |                                       |
       | Mirror reads to new cluster           |
       | (fire-and-forget, don't use result)   |
       +-------------------------------------->|
       
  After warming period (e.g., 30 minutes):
  Switch traffic: v1 -> 0%, v2 -> 100%
```

### Strategy 3: Gradual Traffic Shift

```
  T=0:    Old 100%, New 0%    (new cluster cold)
  T=5m:   Old 90%,  New 10%   (new cluster warming)
  T=10m:  Old 70%,  New 30%   (hit rate climbing)
  T=15m:  Old 50%,  New 50%   (hit rate ~80%)
  T=20m:  Old 20%,  New 80%   (hit rate ~95%)
  T=25m:  Old 0%,   New 100%  (fully warm, hit rate ~99%)
```

---

## 10. Multi-Level Caching (L1 Local + L2 Distributed)

### Architecture

```
  Request Flow:
  
  Client -> App Server -> L1 Cache (in-process) -> L2 Cache (Redis) -> Database
                          ~0.001ms                  ~1ms                ~10ms
  
  +------ App Server 1 ------+    +------ App Server 2 ------+
  |                           |    |                           |
  |  +-------------------+   |    |  +-------------------+   |
  |  | L1 Cache          |   |    |  | L1 Cache          |   |
  |  | (Caffeine/Guava)  |   |    |  | (Caffeine/Guava)  |   |
  |  | Max: 10K entries   |   |    |  | Max: 10K entries   |   |
  |  | TTL: 10 seconds    |   |    |  | TTL: 10 seconds    |   |
  |  +--------+----------+   |    |  +--------+----------+   |
  |           |               |    |           |               |
  +-----------|---------------+    +-----------|---------------+
              |                                |
              +---------- Network ------------>+
              |                                |
       +------+------- L2 Cache (Redis Cluster) --------+------+
       |                                                        |
       |  Max: 10M entries    TTL: 300 seconds                  |
       |  Shared across all app servers                         |
       +--------------------------------------------------------+
              |
              | Network (on L2 miss)
              |
       +------+------- Database (PostgreSQL) ------+
       |                                            |
       +--------------------------------------------+
```

### Numbered Flow

```
  Client            App Server          L1 (Caffeine)     L2 (Redis)        Database
    |                    |                    |                |                |
    | (1) GET /user/1    |                    |                |                |
    |------------------>|                    |                |                |
    |                    | (2) L1.get         |                |                |
    |                    |   ("user:1")       |                |                |
    |                    |------------------->|                |                |
    |                    |                    |                |                |
    |                    | (3) L1 MISS        |                |                |
    |                    |<------------------|                |                |
    |                    |                    |                |                |
    |                    | (4) L2.get         |                |                |
    |                    |   ("user:1")       |                |                |
    |                    |--------------------------------------->|                |
    |                    |                    |                |                |
    |                    | (5) L2 HIT         |                |                |
    |                    |<---------------------------------------|                |
    |                    |                    |                |                |
    |                    | (6) L1.put         |                |                |
    |                    |  ("user:1", data,  |                |                |
    |                    |   TTL=10s)         |                |                |
    |                    |------------------->|                |                |
    |                    |                    |                |                |
    | (7) 200 OK         |                    |                |                |
    |<------------------|                    |                |                |
    |                    |                    |                |                |
    |   Next request within 10s -> L1 HIT (no network call)  |                |
```

### Consistency Challenge

```
  App Server 1 updates "user:1" in L2 (Redis)
  App Server 2's L1 still has OLD "user:1" (TTL not expired)

  Solution: Keep L1 TTL SHORT (5-30 seconds)
  - Short enough that staleness is bounded
  - Long enough to absorb traffic spikes
  
  OR: Use Redis Pub/Sub for L1 invalidation
  App Server 1 -> PUBLISH "invalidate:user:1" -> Redis Pub/Sub
  App Server 2 -> SUBSCRIBE -> receives event -> L1.remove("user:1")
```

### L1 vs L2 Comparison

| Property | L1 (Local) | L2 (Distributed) |
|----------|-----------|------------------|
| Latency | ~0.001ms (in-process) | ~1ms (network) |
| Capacity | Small (10K entries, JVM heap) | Large (10M+ entries, dedicated nodes) |
| Shared | Per app server (not shared) | Shared across all servers |
| TTL | Short (5-30 seconds) | Long (5-30 minutes) |
| Consistency | Potentially stale across servers | Single source (consistent) |
| Technology | Caffeine, Guava Cache | Redis, Memcached |
| Failure impact | None (falls through to L2) | Falls through to DB |

---

## 11. Cache Sizing and Memory Management

### Estimating Cache Size

```
  Formula:
  Total Memory = Number of Entries x Average Entry Size

  Example: User profile cache
  - Entries: 1,000,000 users
  - Avg user JSON: 500 bytes
  - Key overhead: ~50 bytes (key string + metadata)
  - Total per entry: ~550 bytes
  - Total: 1,000,000 x 550 bytes = 550 MB

  With overhead (ConcurrentHashMap, object headers, pointers):
  - Java overhead: ~1.5x raw data size
  - Total: 550 MB x 1.5 = ~825 MB

  Rule of thumb: Provision 2x the raw data size for Java caches.
```

### Memory Budget Allocation

```
  +------ Total Server Memory: 16 GB ------+
  |                                         |
  |  JVM Heap: 8 GB                         |
  |  +-----------------------------------+  |
  |  | Application objects:    2 GB      |  |
  |  | L1 Cache (Caffeine):   1 GB      |  |
  |  | Thread stacks:         0.5 GB    |  |
  |  | GC headroom:           2.5 GB    |  |
  |  | Spare:                 2 GB      |  |
  |  +-----------------------------------+  |
  |                                         |
  |  OS + native: 4 GB                     |
  |  Network buffers: 2 GB                 |
  |  Spare: 2 GB                           |
  +-----------------------------------------+

  Redis dedicated node: 32 GB
  - maxmemory: 24 GB (75% of RAM)
  - OS + fork overhead: 8 GB
  - Entries: 24 GB / 600 bytes = ~40 million entries
```

### Eviction Thresholds

```
  Cache utilization:

  0%       50%        75%        90%        100%
  |---------|----------|----------|----------|
  [  Normal  ][  Normal  ][ Warning ][ Evicting ]
                                       |
                                  Eviction starts
                                  (LRU/LFU/TTL)
  
  Best practice:
  - Set max size at 75% of available memory
  - Monitor eviction rate
  - If eviction rate > 5%: increase cache size or reduce TTL
  - If hit rate < 80%: cache is too small or data is too diverse
```

---

## 12. Comparison Table: When to Use Each Pattern

| Pattern | Read Latency | Write Latency | Consistency | Complexity | Best For |
|---------|-------------|---------------|-------------|-----------|----------|
| **Cache-Aside** | Miss: high, Hit: low | Low (DB only) | Eventual (TTL) | Low | General purpose, most common |
| **Read-Through** | Miss: high, Hit: low | Low (DB only) | Eventual (TTL) | Medium | Simplify application code |
| **Write-Through** | Hit: low | High (cache + DB sync) | Strong | Medium | Read-heavy, consistency needed |
| **Write-Behind** | Hit: low | Very low (cache only) | Eventual | High | Write-heavy workloads |
| **Refresh-Ahead** | Always low (no miss) | N/A | Near-real-time | High | Hot keys, predictable access |

### Decision Tree

```
  What kind of workload?
    |
    +-- Read-heavy (90%+ reads)?
    |     |
    |     +-- Need simple code?
    |     |     -> Cache-Aside (default choice)
    |     |
    |     +-- Want cache to manage DB loading?
    |     |     -> Read-Through
    |     |
    |     +-- Have predictable hot keys?
    |           -> Refresh-Ahead
    |
    +-- Write-heavy (50%+ writes)?
    |     |
    |     +-- Need consistency?
    |     |     -> Write-Through
    |     |
    |     +-- Need low write latency?
    |           -> Write-Behind
    |
    +-- Mixed workload?
          -> Cache-Aside + Write-Through (combo)
```

---

## Interview Q&A

### Q: "What caching strategy would you use for a social media feed?"

> "Cache-Aside for the feed itself -- it's read-heavy, and a short TTL (30s-2min) is acceptable since feeds don't need real-time consistency. For the write path (new post), I'd use event-driven invalidation: when a user posts, publish an event that invalidates followers' cached feeds. For celebrity accounts (hot keys), I'd add an L1 local cache on each app server with a 10-second TTL to absorb the read spike."

### Q: "How do you prevent thundering herd?"

> "Four techniques, in order of preference: (1) Mutex per key -- only one thread fetches from DB, others wait. (2) Stale-while-revalidate -- serve expired data immediately, refresh in background. (3) Probabilistic early expiration -- random threads refresh before TTL, so the cache never fully expires. (4) For distributed systems, use Redis SETNX as a distributed lock so only one instance across the cluster fetches from DB."

### Q: "What's the difference between Cache-Aside and Read-Through?"

> "Who manages the cache miss. In Cache-Aside, the application checks the cache, queries the DB on miss, and populates the cache -- three steps the application owns. In Read-Through, the application just calls cache.get() and the cache itself loads from the DB on a miss. Read-Through simplifies application code but requires a cache library that supports loader functions."

### Q: "How do you handle cache invalidation?"

> "Three approaches depending on staleness tolerance. TTL for most data -- simple, bounded staleness, no infrastructure needed. Event-driven for data that needs near-instant invalidation -- publish a cache invalidation event on every DB write. Versioned keys for API responses -- append a version to the cache key, old versions expire naturally via TTL."

---

## Summary

| Concern | Our Approach | Production Approach |
|---------|-------------|-------------------|
| Caching pattern | Cache-Aside (application manages) | Cache-Aside + Read-Through (library support) |
| Eviction | Strategy pattern (LRU/LFU/TTL) | Redis maxmemory-policy |
| Invalidation | TTL-based | TTL + event-driven (Kafka/Redis Pub/Sub) |
| Thundering herd | Mutex per key | Redis SETNX + stale-while-revalidate |
| Hot keys | N/A (single node) | L1 local cache + key sharding |
| Multi-level | N/A | L1 (Caffeine) + L2 (Redis) |
| Cache warming | N/A | Preload from DB + gradual traffic shift |
