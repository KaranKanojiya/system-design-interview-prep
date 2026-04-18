# Caching Strategy for Rate Limiter Systems

> What to cache, what NOT to cache, and why Redis counters are already a form of caching.

---

## Table of Contents

1. [Caching Taxonomy](#1-caching-taxonomy)
2. [Rules Caching (DO Cache)](#2-rules-caching-do-cache)
3. [Counter Storage in Redis (IS Effectively Caching)](#3-counter-storage-in-redis-is-effectively-caching)
4. [Why NOT to Cache Counters Locally](#4-why-not-to-cache-counters-locally)
5. [Multi-Layer Architecture](#5-multi-layer-architecture)
6. [Rule Hot-Reload and Invalidation](#6-rule-hot-reload-and-invalidation)
7. [Redis Pipelining and Batching](#7-redis-pipelining-and-batching)
8. [Risks and Mitigations](#8-risks-and-mitigations)
9. [Interview Talking Points](#9-interview-talking-points)

---

## 1. Caching Taxonomy

Not everything in a rate limiter should be cached the same way. The key distinction:

| Data | Cache? | Why |
|------|--------|-----|
| **Rate limit rules** | YES -- local in-memory | Rules change rarely; reading from DB per request is wasteful |
| **Counters** | NO -- not locally | Counters must be shared across nodes; local cache = inaccurate counts |
| **Counters in Redis** | Already cached | Redis IS an in-memory store; it is the cache |
| **Client metadata** | YES -- local with TTL | User tier, API key lookups |

```
  +---------------------------------------------------------------+
  |                  WHAT LIVES WHERE                              |
  +---------------------------------------------------------------+
  |                                                               |
  |  App Server (local memory)     Redis (shared memory)     DB   |
  |  +------------------------+    +-------------------+    +---+ |
  |  | Rules cache (HashMap)  |    | Counters (INCR)   |    |   | |
  |  | Client tier cache      |    | Token buckets     |    | R | |
  |  | Config cache           |    | Window counts     |    | u | |
  |  |                        |    | Request logs      |    | l | |
  |  | TTL: 30-60 seconds     |    | TTL: window size  |    | e | |
  |  +------------------------+    +-------------------+    | s | |
  |                                                         +---+ |
  +---------------------------------------------------------------+
```

---

## 2. Rules Caching (DO Cache)

### The Problem

Every rate limit check needs to look up the rule for the given key (e.g., "user:123:/api/orders"). Without caching, every single request hits the database.

```
  Without caching:
  Request --> DB query for rule --> rate limit check --> response
  Adds 2-5ms per request. At 10K req/sec = 10K DB queries/sec for read-only data.

  With caching:
  Request --> local HashMap lookup (nanoseconds) --> rate limit check --> response
  DB query only on cache miss or TTL expiry.
```

### Implementation

```java
public class CachedRuleRepository implements RuleRepository {

    private final RuleRepository delegate;  // The real DB-backed repository
    private final ConcurrentHashMap<String, CachedEntry<RateLimitRule>> cache;
    private final long ttlMs;

    public CachedRuleRepository(RuleRepository delegate, long ttlMs) {
        this.delegate = delegate;
        this.cache = new ConcurrentHashMap<>();
        this.ttlMs = ttlMs;  // e.g., 30_000 (30 seconds)
    }

    @Override
    public Optional<RateLimitRule> findByKey(String key) {
        CachedEntry<RateLimitRule> entry = cache.get(key);

        // Cache hit and not expired
        if (entry != null && !entry.isExpired()) {
            return Optional.ofNullable(entry.value());
        }

        // Cache miss or expired -- fetch from DB
        Optional<RateLimitRule> rule = delegate.findByKey(key);
        cache.put(key, new CachedEntry<>(rule.orElse(null), System.currentTimeMillis() + ttlMs));
        return rule;
    }

    public void invalidate(String key) {
        cache.remove(key);
    }

    public void invalidateAll() {
        cache.clear();
    }

    private record CachedEntry<T>(T value, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
```

### Why 30-60 Second TTL?

| TTL | Staleness Risk | DB Load | Best For |
|-----|---------------|---------|----------|
| 5 seconds | Very low | High (frequent refreshes) | Frequently changing rules |
| **30-60 seconds** | Low | Low | **Most production systems** |
| 5 minutes | Moderate | Very low | Rarely changing rules |
| No TTL (manual invalidation only) | High risk | Minimal | Static rules with Pub/Sub invalidation |

30-60 seconds is the sweet spot: rules change at most a few times per day, so 30 seconds of staleness is acceptable. If an admin doubles a user's rate limit, the worst case is they wait 30 seconds for it to take effect.

---

## 3. Counter Storage in Redis (IS Effectively Caching)

### Redis IS the Cache

A common interview misconception: "we should cache the rate limit counters." But Redis itself is an in-memory data store. The counters are already "cached" in the fastest possible way.

```
  Typical caching layer:
  App --> Cache (Redis) --> Database (PostgreSQL)
                ^
                |
  Rate limiter counters live HERE.
  There is no database behind them.
  Redis IS the source of truth for counters.
```

### Why Counters Do Not Need a Database Behind Redis

| Property | Rules | Counters |
|----------|-------|----------|
| Durability needed? | Yes (persist across restarts) | No (ephemeral by nature) |
| Lifespan | Permanent until admin changes | Window size (seconds to minutes) |
| Recovery on data loss | Reload from DB | Counters reset -- users briefly get extra capacity |
| Source of truth | Database | Redis (in-memory) |

### What Happens if Redis Loses Counter Data

```
  Scenario: Redis restarts, all counters lost
  
  Impact: All users' counters reset to 0
  Duration: Until next window expires (seconds to minutes)
  Risk: Users get up to 1 extra window of requests
  Mitigation: Redis persistence (AOF) or fail-open with alert

  In practice: this is acceptable. A rate limiter briefly allowing
  extra requests is far less harmful than a rate limiter outage.
```

---

## 4. Why NOT to Cache Counters Locally

### The Distributed Counter Problem

```
  BAD: Local counter cache with Redis behind it

  Node A cache: count = 50   ---stale--->   Redis: count = 95
  Node B cache: count = 50   ---stale--->   Redis: count = 95

  Both nodes think count = 50, so both allow requests.
  Actual count = 95, close to limit of 100.
  Result: User gets many more requests than allowed.
```

### Why This Fails

| Problem | Explanation |
|---------|-------------|
| Stale reads | Local cache shows old count; real count is higher |
| Write-behind lag | Local increment not yet flushed to Redis |
| Double counting | Two nodes both increment locally, then both flush |
| Correctness violation | The entire point of rate limiting is accurate counting |

### The Only Exception: Local-Only Rate Limiting

If you accept per-node limits (not global limits), local counters work:

```
  Global limit: 100 req/min across 4 nodes
  Per-node limit: 25 req/min each

  Works if: traffic is evenly distributed
  Fails if: traffic is bursty or uneven
  Wastes capacity: node with 0 traffic still reserves 25 req/min
```

---

## 5. Multi-Layer Architecture

### The Complete Picture

```
  +-------------------------------------------------------------------+
  |                                                                   |
  |  Layer 1: RULES SOURCE OF TRUTH                                  |
  |  +-----------------------------+                                  |
  |  | PostgreSQL / DynamoDB       |  Rules stored durably            |
  |  | RateLimitRule records       |  Admin CRUD API writes here      |
  |  +-------------+---------------+                                  |
  |                 |                                                  |
  |                 | Load on startup + refresh every 30-60s          |
  |                 v                                                  |
  |  Layer 2: LOCAL RULES CACHE (per app server)                     |
  |  +-----------------------------+                                  |
  |  | ConcurrentHashMap           |  Nanosecond lookups              |
  |  | key -> RateLimitRule        |  Avoids DB round-trip            |
  |  | TTL: 30-60 seconds          |  Stale by at most TTL           |
  |  +-------------+---------------+                                  |
  |                 |                                                  |
  |                 | Rule found, now check counter                   |
  |                 v                                                  |
  |  Layer 3: REDIS COUNTERS (shared across all app servers)         |
  |  +-----------------------------+                                  |
  |  | Redis (single instance or   |  Atomic INCR / Lua scripts      |
  |  |         cluster)            |  Sub-ms latency                  |
  |  | key:window -> count         |  TTL = window size               |
  |  | bucket:key -> {tokens, ts}  |  Shared source of truth         |
  |  +-----------------------------+                                  |
  |                                                                   |
  +-------------------------------------------------------------------+
```

### Latency Budget per Rate Limit Check

| Step | Latency | Notes |
|------|---------|-------|
| Local rule lookup (cache hit) | ~100 nanoseconds | ConcurrentHashMap.get() |
| Local rule lookup (cache miss) | 2-5 ms | DB query, then cached |
| Redis counter check | 0.3-1 ms | INCR or Lua script |
| **Total (cache hit)** | **< 1.5 ms** | Acceptable overhead |
| **Total (cache miss)** | **3-6 ms** | Rare, only on TTL expiry |

---

## 6. Rule Hot-Reload and Invalidation

### Three Approaches

#### Approach A: TTL-Based (Simplest)

```
  Cache entry expires after 30 seconds.
  Next request triggers a DB fetch.
  No event system needed.

  Pros: Simplest to implement
  Cons: Up to 30s stale
```

#### Approach B: Event-Driven Invalidation

```
  Admin updates rule
       |
       v
  Rules DB updated
       |
       v
  Publish event: { "action": "invalidate", "key": "user:premium" }
       |
       +---> Redis Pub/Sub ---> All nodes invalidate cache for that key
       |
       +---> OR Kafka topic ---> All nodes invalidate cache for that key
```

```java
// Redis Pub/Sub listener for cache invalidation
public class RuleCacheInvalidationListener {

    private final CachedRuleRepository cachedRepo;

    public void onMessage(String channel, String message) {
        InvalidationEvent event = deserialize(message);
        if ("invalidate".equals(event.action())) {
            cachedRepo.invalidate(event.key());
        } else if ("invalidate_all".equals(event.action())) {
            cachedRepo.invalidateAll();
        }
    }
}
```

#### Approach C: Versioned Cache (Advanced)

```
  Rules table has a version column.
  Cache stores version with each entry.
  Background thread polls: "SELECT MAX(version) FROM rules"
  If version changed, reload only changed rules.

  Pros: Precise invalidation, single lightweight query
  Cons: More complex, requires schema change
```

### Comparison

| Approach | Staleness | Complexity | Dependencies |
|----------|-----------|-----------|-------------|
| TTL-based | Up to TTL | Low | None |
| Pub/Sub events | Near zero | Medium | Redis Pub/Sub or Kafka |
| Versioned cache | Near zero | High | Schema support |
| **Recommended** | **TTL + Pub/Sub** | **Medium** | **Redis (already have it)** |

---

## 7. Redis Pipelining and Batching

### The Problem: Multi-Rule Checks

Some requests need to pass multiple rate limit rules:

```
  Request: POST /api/orders from user:123

  Rules to check:
  1. Global: 10,000 req/min across all users
  2. Per-user: 100 req/min for user:123
  3. Per-endpoint: 50 req/min for user:123:/api/orders

  Naive: 3 Redis round trips = 3ms
  Pipelined: 1 Redis round trip = 1ms
```

### Redis Pipeline Implementation

```java
public List<RateLimitResult> checkMultipleRules(String clientKey, List<RateLimitRule> rules) {
    // Pipeline all checks in a single round trip
    try (Jedis jedis = jedisPool.getResource()) {
        Pipeline pipeline = jedis.pipelined();

        List<Response<?>> responses = new ArrayList<>();
        for (RateLimitRule rule : rules) {
            String counterKey = buildKey(clientKey, rule);
            // Queue commands -- not yet sent to Redis
            responses.add(pipeline.evalsha(
                luaScriptSha,
                Collections.singletonList(counterKey),
                buildArgs(rule)
            ));
        }

        // Send ALL commands in one round trip
        pipeline.sync();

        // Parse results
        return responses.stream()
            .map(this::parseResult)
            .collect(Collectors.toList());
    }
}
```

### Batching Benefits

| Scenario | Without Pipeline | With Pipeline | Savings |
|----------|-----------------|---------------|---------|
| 1 rule per request | 1 round trip | 1 round trip | None |
| 3 rules per request | 3 round trips (~3ms) | 1 round trip (~1ms) | 2ms |
| 5 rules per request | 5 round trips (~5ms) | 1 round trip (~1ms) | 4ms |

---

## 8. Risks and Mitigations

### Risk 1: Stale Rules

```
  Problem: Admin lowers limit from 1000 to 100. Cache still has old rule.
  Window: Up to TTL (30-60 seconds)
  Impact: User gets 1000 req/min for up to 60 more seconds

  Mitigations:
  - Short TTL (30s)
  - Pub/Sub invalidation for immediate propagation
  - "Force refresh" admin API that clears all caches
```

### Risk 2: Redis Failure

```
  Problem: Redis is unreachable. Cannot check or update counters.
  
  +-------------------+
  | Redis is DOWN     |
  +-------------------+
          |
          v
  +-------------------+       +-------------------+
  | Fail-OPEN         |  OR   | Fail-CLOSED       |
  | Allow all requests|       | Reject all requests|
  | Risk: over-limit  |       | Risk: outage       |
  +-------------------+       +-------------------+

  Recommendation: Fail-open with alert + fallback to local counters
```

### Risk 3: Clock Skew Across Nodes

```
  Problem: Node A clock is 2 seconds ahead of Node B.
  Impact on Fixed Window: Different nodes assign requests to different windows.
  Impact on Token Bucket: Refill calculations differ slightly.

  Mitigations:
  - Use Redis server time (TIME command) instead of local time
  - NTP sync to keep clocks within milliseconds
  - Choose algorithms tolerant of small skew (Token Bucket > Fixed Window)
```

### Risk 4: Cache Stampede on TTL Expiry

```
  Problem: Many threads discover cache expired simultaneously.
  All threads hit the DB at once to reload the same rule.

  Mitigations:
  - Lock-based refresh: only one thread reloads, others wait
  - Probabilistic early expiry: randomly refresh slightly before TTL
  - Background refresh: separate thread refreshes cache before TTL
```

```java
// Lock-based refresh to prevent stampede
public Optional<RateLimitRule> findByKey(String key) {
    CachedEntry<RateLimitRule> entry = cache.get(key);
    
    if (entry != null && !entry.isExpired()) {
        return Optional.ofNullable(entry.value());
    }

    // Only one thread refreshes; others get stale value
    Lock lock = refreshLocks.computeIfAbsent(key, k -> new ReentrantLock());
    if (lock.tryLock()) {
        try {
            // Double-check after acquiring lock
            entry = cache.get(key);
            if (entry != null && !entry.isExpired()) {
                return Optional.ofNullable(entry.value());
            }
            Optional<RateLimitRule> rule = delegate.findByKey(key);
            cache.put(key, new CachedEntry<>(rule.orElse(null), now() + ttlMs));
            return rule;
        } finally {
            lock.unlock();
        }
    } else {
        // Another thread is refreshing -- return stale value
        return entry != null ? Optional.ofNullable(entry.value()) : Optional.empty();
    }
}
```

### Risk Summary Table

| Risk | Likelihood | Impact | Mitigation | Residual Risk |
|------|-----------|--------|------------|---------------|
| Stale rules | Medium | Low (temporary) | Short TTL + Pub/Sub | Acceptable |
| Redis failure | Low | High | Fail-open + alert | Brief over-limit |
| Clock skew | Medium | Low | Redis TIME, NTP | Negligible with NTP |
| Cache stampede | Low | Medium | Lock-based refresh | Solved |
| Memory pressure | Low | Low | Bounded cache size | LRU eviction |

---

## 9. Interview Talking Points

### The 30-Second Answer

> "In a rate limiter, we cache rate limit RULES locally on each app server with a 30-60 second TTL. Rules change rarely, so this avoids a database round trip on every request. We do NOT cache counters locally -- counters must be shared across all nodes for accuracy, so they live in Redis. Redis itself is an in-memory store, so the counters are already in the fastest possible location. The total overhead per rate limit check is under 1.5 milliseconds: a nanosecond local cache lookup for the rule, plus a sub-millisecond Redis round trip for the counter."

### Follow-Up: "What if the rules cache has stale data?"

> "With a 30-second TTL, the worst case is a user operates under the old rule for 30 seconds. For critical rule changes -- like blocking an abusive user -- we publish a cache invalidation event via Redis Pub/Sub so all nodes update within milliseconds. We also expose a force-refresh admin endpoint for emergencies."

### Follow-Up: "Why not cache counters in local memory too?"

> "If we cached counters locally, each node would have its own count. With 4 nodes and a limit of 100, a user could make 400 requests before any node rejects them. The entire point of rate limiting is accurate shared counting, which requires a centralized store like Redis. The only exception is if you accept per-node limits instead of global limits, which wastes capacity when traffic is unevenly distributed."

### Follow-Up: "What about Redis latency -- is it fast enough?"

> "Redis rate limit checks typically take 0.3-1ms over a local network. For comparison, a typical API request takes 50-500ms for business logic. Adding 1ms for rate limiting is negligible. If even that is too much, Redis pipelining lets us batch multiple rule checks into a single round trip."

---

## Architecture Decision Record: Caching

```
  DECISION: Cache rules locally, counters in Redis only.

  STATUS: Accepted

  CONTEXT:
  - Rate limit checks happen on every API request (hot path)
  - Rules change rarely (per day), counters change constantly (per request)
  - Multiple app server nodes must agree on counter values

  DECISION:
  - Rules: local ConcurrentHashMap, 30s TTL, Pub/Sub invalidation
  - Counters: Redis only, no local caching
  - Fail-open on Redis failure

  CONSEQUENCES:
  + Sub-1.5ms rate limit checks
  + Accurate shared counters
  + Rules stale by at most 30s (acceptable)
  - Redis is a critical dependency (mitigated by Sentinel + fail-open)
  - Local cache consumes memory (negligible -- rules are small objects)
```
